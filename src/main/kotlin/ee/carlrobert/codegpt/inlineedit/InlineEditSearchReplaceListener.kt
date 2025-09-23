package ee.carlrobert.codegpt.inlineedit

import com.intellij.diff.DiffManager
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.openapi.components.service
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.conversations.Conversation
import ee.carlrobert.codegpt.toolwindow.chat.parser.ReplaceWaiting
import ee.carlrobert.codegpt.toolwindow.chat.parser.SearchReplace
import ee.carlrobert.codegpt.toolwindow.chat.parser.SearchWaiting
import ee.carlrobert.codegpt.toolwindow.chat.parser.SseMessageParser
import ee.carlrobert.codegpt.toolwindow.chat.parser.Text
import ee.carlrobert.codegpt.toolwindow.chat.parser.CodeHeader
import ee.carlrobert.codegpt.toolwindow.chat.parser.Code
import ee.carlrobert.codegpt.toolwindow.chat.parser.CodeEnd
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.codegpt.ui.components.InlineEditChips
import ee.carlrobert.codegpt.util.EditorDiffUtil
import ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowContentManager
import ee.carlrobert.codegpt.conversations.ConversationService
import ee.carlrobert.codegpt.conversations.message.Message
import ee.carlrobert.llm.client.openai.completion.ErrorDetails
import ee.carlrobert.llm.completion.CompletionEventListener
import okhttp3.sse.EventSource
import java.awt.Color
import java.awt.Font
import java.util.*
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JLabel
import kotlin.concurrent.schedule

/**
 * Simplified completion listener for Inline Edit feature that handles SEARCH/REPLACE blocks
 * for the current editor file only.
 */
class InlineEditSearchReplaceListener(
    private val editor: EditorEx,
    private val observableProperties: ObservableProperties,
    private val selectionTextRange: TextRange,
    private val requestId: Long,
    private val conversation: Conversation
) : CompletionEventListener<String> {

    private val project: Project = editor.project!!
    private val logger = Logger.getInstance(InlineEditSearchReplaceListener::class.java)
    private val sseMessageParser = SseMessageParser()
    private val accumulatedSearchReplaceSegments = mutableListOf<SearchReplace>()
    private val assistantResponseBuilder = StringBuilder()
    private var isStreamingComplete = false
    private var isStopping = false
    private var previewSessionStarted = false
    private var hasReceivedMessage = false

    private val searchHighlighters = mutableListOf<RangeHighlighter>()
    private var currentSearchPattern: String? = null
    private val highlightDebounceAlarm = Alarm()
    private var hintComponent: JComponent? = null
    private var lastHintMessage: String? = null
    private val waitingAlarm = Alarm()

    private val searchHighlightColor = JBColor(
        Color(255, 235, 59, 80),
        Color(255, 235, 59, 60)
    )

    private val replaceReadyColor = JBColor(
        Color(59, 255, 149, 80),
        Color(59, 255, 149, 60)
    )

    enum class HighlightState {
        SEARCHING,
        FOUND,
        REPLACING,
        ERROR
    }

    sealed class ValidationResult {
        object Success : ValidationResult()
        data class Error(val message: String) : ValidationResult()
    }

    private fun validateSearchReplacePattern(search: String, replace: String): ValidationResult {
        val searchText = search.trim()
        val replaceText = replace.trim()

        if (searchText == replaceText) {
            return ValidationResult.Error("No changes: search and replace content are identical")
        }

        if (searchText.isEmpty()) {
            return ValidationResult.Error("Empty search pattern")
        }

        return ValidationResult.Success
    }

    init {
        waitingAlarm.addRequest({
            if (!hasReceivedMessage) {
                showInlineHint(CodeGPTBundle.get("inlineEdit.status.waiting"))
            }
        }, 1500)
    }

    private fun deduplicateSearchReplaceBlocks(blocks: List<Pair<String, String>>): List<Pair<String, String>> {
        val deduplicated = mutableListOf<Pair<String, String>>()
        val seenPatterns = mutableSetOf<String>()

        for ((search, replace) in blocks) {
            val normalizedSearch = search.trim()

            if (seenPatterns.contains(normalizedSearch)) continue

            if (normalizedSearch == replace.trim()) continue

            val isSubsumed = deduplicated.any { (existingSearch, _) ->
                existingSearch.contains(normalizedSearch) || normalizedSearch.contains(
                    existingSearch.trim()
                )
            }

            if (!isSubsumed) {
                deduplicated.add(Pair(search, replace))
                seenPatterns.add(normalizedSearch)
            }
        }

        return deduplicated
    }

    private fun applySimpleSearchReplace(
        originalContent: String,
        searchReplaceBlocks: List<Pair<String, String>>
    ): String {
        val deduplicatedBlocks = deduplicateSearchReplaceBlocks(searchReplaceBlocks)
        var currentContent = originalContent
        var totalReplacements = 0

        val docEol = if (originalContent.contains("\r\n")) "\r\n" else "\n"

        for ((search, replace) in deduplicatedBlocks) {
            val searchText = search.trim().replace("\r\n", "\n").replace("\n", docEol)
            val replaceText = replace.trim().replace("\r\n", "\n").replace("\n", docEol)

            if (searchText.isEmpty() && originalContent.isNotEmpty()) {
                continue
            }

            var replacementCount =
                if (searchText.isEmpty()) 0 else currentContent.split(searchText).size - 1

            if (replacementCount == 0 && search.contains("...")) {
                val searchStart = searchText.lines().firstOrNull()?.trim() ?: ""
                if (searchStart.isNotEmpty() && currentContent.contains(searchStart)) {
                    val startIndex = currentContent.indexOf(searchStart)
                    if (startIndex >= 0) {
                        val lines = currentContent.split(docEol)
                        val startLine =
                            currentContent.substring(0, startIndex).split(docEol).size - 1
                        val searchLines = searchText.split(docEol).size
                        val endLine = minOf(startLine + searchLines, lines.size)

                        val actualPattern = lines.subList(startLine, endLine).joinToString(docEol)

                        if (currentContent.contains(actualPattern)) {
                            currentContent = currentContent.replace(actualPattern, replaceText)
                            totalReplacements++
                            continue
                        }
                    }
                }
            }

            if (replacementCount == 0) {
                val tokens = searchText.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (tokens.isNotEmpty()) {
                    val pattern = tokens.joinToString("\\s+") { Regex.escape(it) }
                    val regex =
                        Regex(pattern, setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))
                    val match = regex.find(currentContent)
                    if (match != null) {
                        currentContent = currentContent.replaceRange(match.range, replaceText)
                        totalReplacements++
                        continue
                    }
                }
                continue
            }

            currentContent = currentContent.replace(searchText, replaceText)
            totalReplacements += replacementCount
        }

        return currentContent
    }

    private fun isCurrentRequest(): Boolean {
        val keyValue = editor.getUserData(REQUEST_ID_KEY)
        return keyValue == null || keyValue == requestId
    }

    private fun clearAllHighlights() {
        searchHighlighters.forEach {
            editor.markupModel.removeHighlighter(it)
        }
        searchHighlighters.clear()
    }

    private fun createHighlighter(
        range: TextRange,
        color: JBColor,
        tooltip: String
    ): RangeHighlighter = editor.markupModel.addRangeHighlighter(
        range.startOffset,
        range.endOffset,
        HighlighterLayer.SELECTION,
        TextAttributes().apply {
            backgroundColor = color
            effectType = EffectType.ROUNDED_BOX
            effectColor = color.darker()
        },
        HighlighterTargetArea.EXACT_RANGE
    ).apply {
        errorStripeTooltip = tooltip
    }

    private fun ensureVisible(offset: Int) {
        val logicalPosition = editor.offsetToLogicalPosition(offset)
        editor.scrollingModel.scrollTo(logicalPosition, ScrollType.MAKE_VISIBLE)
    }

    private fun targetRange(): TextRange {
        return if (selectionTextRange.startOffset < selectionTextRange.endOffset) {
            selectionTextRange
        } else {
            TextRange(0, editor.document.textLength)
        }
    }

    override fun onMessage(message: String, eventSource: EventSource) {
        if (!isCurrentRequest()) return
        if (isStopping) return
        hasReceivedMessage = true

        assistantResponseBuilder.append(message)

        sseMessageParser.parse(message).forEachIndexed { _, segment ->
            when (segment) {
                is SearchReplace -> {
                    when (val validation =
                        validateSearchReplacePattern(segment.search, segment.replace)) {
                        is ValidationResult.Success -> {
                            processCurrentFileSearchReplace(segment)
                        }

                        is ValidationResult.Error -> {
                            runInEdt {
                                OverlayUtil.showNotification(
                                    "Warning: ${validation.message}",
                                    NotificationType.WARNING
                                )
                            }
                            processCurrentFileSearchReplace(segment)
                        }
                    }
                }

                is ReplaceWaiting -> {
                    val pattern = currentSearchPattern
                    if (pattern != null) {
                        runInEdt {
                            if (searchHighlighters.isEmpty()) {
                                highlightSearchRegions(pattern, true)
                            }
                            updateHighlightState(
                                HighlightState.FOUND,
                                CodeGPTBundle.get("inlineEdit.status.preparingReplacement")
                            )
                        }
                    }
                }

                is SearchWaiting -> {
                    currentSearchPattern = segment.search
                    if (segment.search.isNotEmpty()) {
                        val pattern = segment.search
                        highlightDebounceAlarm.cancelAllRequests()
                        highlightDebounceAlarm.addRequest({
                            runInEdt { if (!isStopping) highlightSearchRegions(pattern, false) }
                        }, 120)
                    }
                }

                else -> {
                }
            }
        }
    }

    override fun onComplete(completionMessageBuilder: StringBuilder) {
        if (!isCurrentRequest()) return

        runInEdt {
            isStreamingComplete = true

            clearAllHighlights()
            highlightDebounceAlarm.cancelAllRequests()
            hintComponent?.let {
                editor.contentComponent.remove(it)
                hintComponent = null
            }

            val message = conversation.messages.last()
            message.response = computeAssistantResponse()

            val hadChanges = showFinalDiff()

            val inlay = editor.getUserData(InlineEditInlay.INLAY_KEY)
            inlay?.observableProperties?.hasPendingChanges?.set(hadChanges)
            inlay?.setThinkingVisible(false)
            inlay?.setInlineEditControlsVisible(hadChanges)
            inlay?.onCompletionFinished()

            val statusComponent = (editor.scrollPane as JBScrollPane).statusComponent
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_ACCEPT_ALL_CHIP)
                ?.let { statusComponent.remove(it) }
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_REJECT_ALL_CHIP)
                ?.let { statusComponent.remove(it) }
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_COMPARE_LINK)
                ?.let { statusComponent.remove(it) }

            if (hadChanges) {
                val acceptChip = InlineEditChips.acceptAll {
                    AcceptAllInlineEditAction.acceptAll(editor)
                }.apply { border = JBUI.Borders.empty(0, 6) }
                val rejectChip = InlineEditChips.rejectAll {
                    RejectAllInlineEditAction.rejectAll(editor)
                }.apply { border = JBUI.Borders.empty(0, 6) }

                val compareLink = createDiffViewerLink()

                if (statusComponent != null) {
                    statusComponent.border = JBUI.Borders.empty(6)
                    statusComponent.add(compareLink)
                    statusComponent.add(acceptChip)
                    statusComponent.add(Box.createHorizontalStrut(6))
                    statusComponent.add(rejectChip)
                }

                editor.putUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_ACCEPT_ALL_CHIP, acceptChip)
                editor.putUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_REJECT_ALL_CHIP, rejectChip)
                editor.putUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_COMPARE_LINK, compareLink)

                statusComponent.revalidate()
                statusComponent.repaint()
            } else {
                statusComponent.revalidate()
                statusComponent.repaint()
            }
            stopLoading()
        }
    }

    private fun createDiffViewerLink(): ActionLink {
        return ActionLink("Open in Diff Viewer") {
            try {
                val originalDocText = runReadAction { editor.document.text }
                val usedRange = targetRange()
                val originalSelection = runReadAction { editor.document.getText(usedRange) }
                val modifiedSelection = applyAllSearchReplaceOperations(
                    originalSelection,
                    accumulatedSearchReplaceSegments
                )

                val newContent =
                    if (usedRange.startOffset == 0 && usedRange.endOffset == originalDocText.length) {
                        modifiedSelection
                    } else buildString(originalDocText.length + modifiedSelection.length) {
                        append(originalDocText, 0, usedRange.startOffset)
                        append(modifiedSelection)
                        append(originalDocText, usedRange.endOffset, originalDocText.length)
                    }

                val originalVf = editor.virtualFile ?: return@ActionLink
                val tempFile = LightVirtualFile(originalVf.name, newContent)
                val diffRequest =
                    EditorDiffUtil.createDiffRequest(project, tempFile, originalVf)
                DiffManager.getInstance().showDiff(project, diffRequest)
            } catch (e: Exception) {
                OverlayUtil.showNotification(
                    "Failed to open diff: ${e.message}",
                    NotificationType.ERROR
                )
            }
        }.apply {
            icon = AllIcons.Actions.Diff
            toolTipText = CodeGPTBundle.get("editor.diff.title")
            border = JBUI.Borders.empty(0, 6)
        }
    }

    private fun buildAssistantSummaryForConversation(): String {
        val vf = editor.virtualFile
        val language = vf?.extension ?: "txt"
        val path = vf?.path ?: "untitled"

        if (accumulatedSearchReplaceSegments.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("```$language:$path\n")
        accumulatedSearchReplaceSegments.forEach { seg ->
            val search = seg.search.trim()
            val replace = seg.replace.trim()
            if (search.isEmpty() && replace.isEmpty()) return@forEach
            sb.append("SEARCH\n")
            sb.append(search)
            sb.append("\nREPLACE\n")
            sb.append(replace)
            sb.append("\n---\n")
        }
        sb.append("```\n")
        return sb.toString()
    }

    private fun computeAssistantResponse(): String {
        val raw = assistantResponseBuilder.toString().trim()
        if (raw.isNotEmpty()) return raw
        return buildAssistantSummaryForConversation()
    }

    private fun openConversationInNewChat() {
        val inlay = editor.getUserData(InlineEditInlay.INLAY_KEY)
        if (inlay != null) {
            inlay.openOrCreateChatFromSession(conversation)
            return
        }

        val newConversation = Conversation().apply {
            title = conversation.title
            projectPath = conversation.projectPath
        }

        conversation.messages.forEach { original ->
            val copy = Message(original.prompt, original.response)
            copy.referencedFilePaths = original.referencedFilePaths
            copy.conversationsHistoryIds = original.conversationsHistoryIds
            copy.imageFilePath = original.imageFilePath
            copy.documentationDetails = original.documentationDetails
            copy.personaName = original.personaName
            newConversation.addMessage(copy)
        }

        ConversationService.getInstance().addConversation(newConversation)
        ConversationService.getInstance().saveConversation(newConversation)
        project.service<ChatToolWindowContentManager>().displayConversation(newConversation)
    }

    override fun onError(error: ErrorDetails, ex: Throwable) {
        if (!isCurrentRequest()) return

        runInEdt {
            clearAllHighlights()
            highlightDebounceAlarm.cancelAllRequests()
            hintComponent?.let {
                editor.contentComponent.remove(it)
                hintComponent = null
            }
            val pop = editor.getUserData(InlineEditInlay.INLAY_KEY)
            pop?.setThinkingVisible(false)
            pop?.setInlineEditControlsVisible(false)
        }

        OverlayUtil.showNotification(
            error.message,
            NotificationType.ERROR,
            NotificationAction.createSimpleExpiring("Upgrade plan") {
                BrowserUtil.open("https://tryproxy.io/#pricing")
            },
        )
        unlockEditorOnError()
        stopLoading()
    }

    private fun processCurrentFileSearchReplace(segment: SearchReplace) {
        accumulatedSearchReplaceSegments.add(segment)

        try {
            val result = filterApplicableSegments(accumulatedSearchReplaceSegments)
            if (result.filteredCount > 0) {
                showFilteredWarning(result)
            }
            val range = targetRange()
            val originalContent = runReadAction { editor.document.getText(range) }
            val modifiedSoFar = applySimpleSearchReplace(originalContent, result.pairs)

            val existingSession = editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)

            if (existingSession == null) {
                val session = InlineEditSession.start(
                    project,
                    editor,
                    range,
                    modifiedSoFar
                )
                session.setInteractive(true)
                previewSessionStarted = true
            } else {
                existingSession.updateProposedText(modifiedSoFar, interactive = true)
            }
        } catch (e: Exception) {
            logger.error("Error while processing segment", e)
        }
    }

    private fun showFinalDiff(): Boolean {
        if (!isStreamingComplete) {
            return false
        }

        try {
            val range = targetRange()
            val originalContent = runReadAction { editor.document.getText(range) }
            val modifiedContent =
                applyAllSearchReplaceOperations(originalContent, accumulatedSearchReplaceSegments)
            if (modifiedContent == originalContent) {
                val noChangesMsg = CodeGPTBundle.get("inlineEdit.status.noChanges")
                showInlineHint(noChangesMsg)
                val action = NotificationAction.createSimpleExpiring(
                    CodeGPTBundle.get("inlineEdit.action.openInChat")
                ) {
                    openConversationInNewChat()
                }
                OverlayUtil.showNotification(noChangesMsg, NotificationType.INFORMATION, action)
                return false
            }

            val existingSession = editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)
            if (existingSession == null) {
                InlineEditSession.start(project, editor, range, modifiedContent)
            } else {
                existingSession.updateProposedText(modifiedContent, interactive = true)
                if (!existingSession.hasPendingHunks()) {
                    val noChangesMsg = CodeGPTBundle.get("inlineEdit.status.noChanges")
                    showInlineHint(noChangesMsg)
                    val action = NotificationAction.createSimpleExpiring(
                        CodeGPTBundle.get("inlineEdit.action.openInChat")
                    ) {
                        openConversationInNewChat()
                    }
                    OverlayUtil.showNotification(noChangesMsg, NotificationType.INFORMATION, action)
                    return false
                }
            }
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)?.setInteractive(true)
        } catch (e: Exception) {
            logger.warn("Failed to build final diff", e)
            return false
        }
        return true
    }

    private fun finalizePartialResults(): Boolean {
        try {
            val range = targetRange()
            val originalContent = runReadAction { editor.document.getText(range) }
            val modifiedContent =
                applyAllSearchReplaceOperations(originalContent, accumulatedSearchReplaceSegments)
            if (modifiedContent == originalContent) {
                val noChangesMsg = CodeGPTBundle.get("inlineEdit.status.noChanges")
                showInlineHint(noChangesMsg)
                val action = NotificationAction.createSimpleExpiring(
                    CodeGPTBundle.get("inlineEdit.action.openInChat")
                ) {
                    openConversationInNewChat()
                }
                OverlayUtil.showNotification(noChangesMsg, NotificationType.INFORMATION, action)
                return false
            }

            val existingSession = editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)
            if (existingSession == null) {
                InlineEditSession.start(project, editor, range, modifiedContent)
            } else {
                existingSession.updateProposedText(modifiedContent, interactive = true)
                if (!existingSession.hasPendingHunks()) {
                    val noChangesMsg = CodeGPTBundle.get("inlineEdit.status.noChanges")
                    showInlineHint(noChangesMsg)
                    val action = NotificationAction.createSimpleExpiring(
                        CodeGPTBundle.get("inlineEdit.action.openInChat")
                    ) {
                        openConversationInNewChat()
                    }
                    OverlayUtil.showNotification(noChangesMsg, NotificationType.INFORMATION, action)
                    return false
                }
            }
            editor.getUserData(CodeGPTKeys.EDITOR_INLINE_EDIT_SESSION)?.setInteractive(true)
        } catch (e: Exception) {
            logger.warn("Failed to finalize partial results", e)
            return false
        }
        return true
    }

    private fun applyAllSearchReplaceOperations(
        originalContent: String,
        searchReplaceSegments: List<SearchReplace>
    ): String {
        val filtered = filterApplicableSegments(searchReplaceSegments)
        if (filtered.filteredCount > 0) {
            showFilteredWarning(filtered)
        }
        return applySimpleSearchReplace(originalContent, filtered.pairs)
    }

    private fun filterApplicableSegments(segments: List<SearchReplace>): FilterResult {
        val vf = editor.virtualFile
        val currentPath = vf?.path
        val fileName = vf?.name
        val fileExt = vf?.extension?.lowercase()
        return InlineEditFilter.filterSegments(currentPath, fileName, fileExt, segments)
    }

    private fun showFilteredWarning(result: FilterResult) {
        if (result.filteredCount <= 0) return
        runInEdt {
            showInlineHint("Ignored ${result.filteredCount} invalid block(s)")
        }
    }

    private fun stopLoading() {
        observableProperties.loading.set(false)
        project.let {
            CompletionProgressNotifier.Companion.update(it, false)
        }

        val inlay = editor.getUserData(InlineEditInlay.INLAY_KEY)
        inlay?.onCompletionFinished()
    }

    private fun unlockEditorOnError() {
        if (!editor.document.isWritable) {
            editor.document.setReadOnly(false)
        }
    }

    fun stopGenerating() {
        runInEdt {
            isStopping = true
            highlightDebounceAlarm.cancelAllRequests()
            waitingAlarm.cancelAllRequests()

            clearAllHighlights()

            hintComponent?.let {
                editor.contentComponent.remove(it)
                hintComponent = null
                lastHintMessage = null
            }

            if (accumulatedSearchReplaceSegments.isNotEmpty()) {
                finalizePartialResults()
            }

            showInlineHint("Generation stopped")
        }
    }

    fun dispose() {
        clearAllHighlights()
        highlightDebounceAlarm.cancelAllRequests()
        waitingAlarm.cancelAllRequests()
        hintComponent?.let {
            editor.contentComponent.remove(it)
        }
        hintComponent = null
        lastHintMessage = null

        editor.putUserData(LISTENER_KEY, null)
    }

    private fun findPatternInContent(
        content: String,
        pattern: String,
        fuzzyMatch: Boolean = true
    ): List<TextRange> {
        val matches = mutableListOf<TextRange>()
        val cleanPattern = pattern.trim()

        if (cleanPattern.isEmpty()) {
            return matches
        }

        var index = content.indexOf(cleanPattern)
        while (index >= 0) {
            matches.add(TextRange(index, index + cleanPattern.length))
            index = content.indexOf(cleanPattern, index + 1)
        }

        if (matches.isEmpty() && fuzzyMatch && (pattern.contains("...") || cleanPattern.length < pattern.length)) {
            val partialMatches = findPartialMatches(content, cleanPattern)
            matches.addAll(partialMatches)
        }

        return matches
    }

    private fun findPartialMatches(content: String, pattern: String): List<TextRange> {
        val identifiers = extractIdentifiers(pattern)
        if (identifiers.isEmpty()) {
            val firstLine = pattern.lines().firstOrNull()?.trim()
            if (!firstLine.isNullOrEmpty() && content.contains(firstLine)) {
                val index = content.indexOf(firstLine)
                return listOf(expandToLogicalBlock(content, index))
            }
            return emptyList()
        }

        val matches = mutableListOf<TextRange>()
        val lines = content.lines()

        for (i in lines.indices) {
            if (identifiers.all { identifier -> lines[i].contains(identifier) }) {
                val blockRange = expandToLogicalBlock(content, getLineStartOffset(content, i))
                matches.add(blockRange)
            }
        }

        return matches
    }

    private fun extractIdentifiers(pattern: String): List<String> {
        val identifierRegex =
            "\\b(class|function|def|var|val|let|const|public|private)\\s+(\\w+)".toRegex()
        val methodRegex = "\\b(\\w+)\\s*\\(".toRegex()
        val variableRegex = "\\b[a-zA-Z_][a-zA-Z0-9_]{2,}\\b".toRegex()

        val identifiers = mutableSetOf<String>()

        identifierRegex.findAll(pattern).forEach { match ->
            identifiers.add(match.groupValues[2])
        }

        methodRegex.findAll(pattern).forEach { match ->
            identifiers.add(match.groupValues[1])
        }

        variableRegex.findAll(pattern).forEach { match ->
            val identifier = match.value
            if (identifier.length > 2 && !identifier.matches("\\d+".toRegex())) {
                identifiers.add(identifier)
            }
        }

        return identifiers.toList().take(3)
    }

    private fun expandToLogicalBlock(content: String, charOffset: Int): TextRange {
        val lines = content.lines()
        val lineIndex = getLineIndex(content, charOffset)

        if (lineIndex >= lines.size) {
            return TextRange(charOffset, charOffset)
        }

        var startLine = lineIndex
        var endLine = lineIndex
        var braceCount = 0

        for (i in lineIndex downTo 0) {
            val line = lines[i]
            if (line.contains("{")) braceCount++
            if (line.contains("}")) braceCount--

            if (braceCount > 0 || isBlockStart(line)) {
                startLine = i
                break
            }
        }

        braceCount = 0
        for (i in lineIndex until lines.size) {
            val line = lines[i]
            if (line.contains("{")) braceCount++
            if (line.contains("}")) braceCount--

            if (braceCount == 0 && line.contains("}") && i > lineIndex) {
                endLine = i
                break
            }
        }

        return convertLinesToTextRange(content, startLine, endLine)
    }

    private fun isBlockStart(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("class ") ||
                trimmed.startsWith("function ") ||
                trimmed.startsWith("def ") ||
                trimmed.startsWith("public ") ||
                trimmed.startsWith("private ") ||
                trimmed.contains("{") ||
                trimmed.endsWith(":")
    }

    private fun getLineIndex(content: String, charOffset: Int): Int {
        return content.substring(0, charOffset).count { it == '\n' }
    }

    private fun getLineStartOffset(content: String, lineIndex: Int): Int {
        val lines = content.lines()
        var offset = 0

        for (i in 0 until lineIndex) {
            if (i < lines.size) {
                offset += lines[i].length + 1
            }
        }

        return offset
    }

    private fun convertLinesToTextRange(content: String, startLine: Int, endLine: Int): TextRange {
        val lines = content.lines()

        var startOffset = 0
        for (i in 0 until startLine) {
            if (i < lines.size) {
                startOffset += lines[i].length + 1
            }
        }

        var endOffset = startOffset
        for (i in startLine..endLine) {
            if (i < lines.size) {
                endOffset += lines[i].length
                if (i < lines.size - 1) endOffset += 1
            }
        }

        return TextRange(startOffset, endOffset)
    }

    private fun highlightSearchRegions(pattern: String, isReplaceReady: Boolean = false) {
        runInEdt {
            if (isStopping) return@runInEdt
            clearAllHighlights()

            if (pattern.isEmpty()) {
                return@runInEdt
            }

            val hasSelection = selectionTextRange.startOffset < selectionTextRange.endOffset
            val (content, baseOffset) = if (hasSelection) {
                editor.document.getText(selectionTextRange) to selectionTextRange.startOffset
            } else {
                editor.document.text to 0
            }

            val matches = findPatternInContent(content, pattern)
            if (matches.isEmpty()) {
                showPatternNotFoundHint(pattern)
                return@runInEdt
            }

            matches.forEach { range ->

                val absoluteRange =
                    TextRange(baseOffset + range.startOffset, baseOffset + range.endOffset)
                val color = if (isReplaceReady) replaceReadyColor else searchHighlightColor
                val tooltip = if (isReplaceReady) CodeGPTBundle.get("inlineEdit.tooltip.ready")
                else CodeGPTBundle.get("inlineEdit.tooltip.searching")

                searchHighlighters.add(createHighlighter(absoluteRange, color, tooltip))
            }

            if (matches.isNotEmpty()) {
                ensureVisible(baseOffset + matches.first().startOffset)
            }
        }
    }

    private fun updateHighlightState(state: HighlightState, message: String? = null) {
        if (isStopping) return
        val color = when (state) {
            HighlightState.SEARCHING -> searchHighlightColor
            HighlightState.FOUND -> replaceReadyColor
            HighlightState.REPLACING -> JBColor(
                Color(59, 149, 255, 80),
                Color(59, 149, 255, 60)
            )

            HighlightState.ERROR -> JBColor(Color(255, 59, 59, 80), Color(255, 59, 59, 60))
        }

        val ranges = searchHighlighters.map { TextRange(it.startOffset, it.endOffset) }
        clearAllHighlights()
        val tooltip = when (state) {
            HighlightState.SEARCHING -> CodeGPTBundle.get("inlineEdit.tooltip.searching")
            HighlightState.FOUND, HighlightState.REPLACING -> CodeGPTBundle.get("inlineEdit.tooltip.ready")
            HighlightState.ERROR -> message ?: CodeGPTBundle.get("inlineEdit.tooltip.searching")
        }
        ranges.forEach { r ->
            searchHighlighters.add(createHighlighter(r, color, tooltip))
        }

        if (message != null) {
            showInlineHint(message)
        }
    }

    private fun showPatternNotFoundHint(pattern: String) {
        val shortPattern = if (pattern.length > 30) "${pattern.take(30)}..." else pattern
        showInlineHint(
            CodeGPTBundle.get("inlineEdit.hint.searchingFor", shortPattern)
        )
    }

    private fun showInlineHint(message: String) {
        runInEdt {
            if (lastHintMessage == message && hintComponent != null) return@runInEdt
            hintComponent?.let { editor.contentComponent.remove(it) }

            val hintComponentToAdd: JComponent = run {
                val noChangesMsg = CodeGPTBundle.get("inlineEdit.status.noChanges")
                if (message == noChangesMsg) {
                    val container = Box.createHorizontalBox()
                    val label = JLabel(message).apply {
                        foreground = JBColor.GRAY
                        font = font.deriveFont(Font.ITALIC, 12f)
                    }
                    val link = ActionLink(CodeGPTBundle.get("inlineEdit.action.openInChat")) {
                        openConversationInNewChat()
                    }.apply {
                        border = JBUI.Borders.emptyLeft(8)
                    }
                    container.border = JBUI.Borders.empty(2, 8)
                    container.background = editor.backgroundColor
                    container.isOpaque = true
                    container.add(label)
                    container.add(link)
                    container
                } else {
                    JLabel(message).apply {
                        foreground = JBColor.GRAY
                        font = font.deriveFont(Font.ITALIC, 12f)
                        border = JBUI.Borders.empty(2, 8)
                        background = editor.backgroundColor
                        isOpaque = true
                    }
                }
            }

            val targetOffset = if (searchHighlighters.isNotEmpty()) {
                searchHighlighters.first().startOffset
            } else {
                selectionTextRange.startOffset
            }

            val comp = editor.contentComponent
            val point = editor.visualPositionToXY(editor.offsetToVisualPosition(targetOffset))
            val visible = comp.visibleRect

            val prefW = 300
            val prefH = 20
            var x = point.x
            var y = point.y - 25

            if (x < visible.x) x = visible.x + JBUI.scale(8)
            if (y < visible.y) y = visible.y + JBUI.scale(8)
            if (x + prefW > visible.x + visible.width) x =
                visible.x + visible.width - prefW - JBUI.scale(8)
            if (y + prefH > visible.y + visible.height) y =
                visible.y + visible.height - prefH - JBUI.scale(8)

            hintComponentToAdd.setBounds(x, y, prefW, prefH)

            comp.add(hintComponentToAdd)
            hintComponent = hintComponentToAdd
            lastHintMessage = message

            Timer().schedule(3000) {
                runInEdt {
                    if (hintComponent == hintComponentToAdd) {
                        comp.remove(hintComponentToAdd)
                        comp.repaint()
                        hintComponent = null
                        lastHintMessage = null
                    }
                }
            }
        }
    }

    fun showHint(message: String) {
        showInlineHint(message)
    }

    companion object {
        val LISTENER_KEY =
            Key.create<InlineEditSearchReplaceListener>("InlineEditSearchReplaceListener")
        val REQUEST_ID_KEY = Key.create<Long>("InlineEditRequestId")
    }
}
