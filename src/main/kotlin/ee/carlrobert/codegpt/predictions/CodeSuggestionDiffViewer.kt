package ee.carlrobert.codegpt.predictions

import com.intellij.codeInsight.inline.completion.session.InlineCompletionContext
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContext
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffChange
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.util.DiffUtil
import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.*
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.application
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.codecompletions.edit.GrpcClientService
import ee.carlrobert.codegpt.codecompletions.edit.NextEditCoordinator
import ee.carlrobert.codegpt.telemetry.ui.preferences.TelemetryConfigurable
import ee.carlrobert.service.NextEditResponse
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.util.*
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.max

class CodeSuggestionDiffViewer(
    request: DiffRequest,
    val nextEditResponse: NextEditResponse,
    private val mainEditor: Editor,
) : UnifiedDiffViewer(MyDiffContext(mainEditor.project), request), Disposable {

    private val popup: JBPopup = createSuggestionDiffPopup(component)
    private val visibleAreaListener: VisibleAreaListener
    private val documentListener: DocumentListener
    private val grpcService = project?.service<GrpcClientService>()

    private var applyInProgress = false

    init {
        visibleAreaListener = getVisibleAreaListener()
        documentListener = getDocumentListener()
        setupDiffEditor()
        mainEditor.scrollingModel.addVisibleAreaListener(visibleAreaListener)
        mainEditor.document.addDocumentListener(documentListener, this)

        Disposer.register(popup) { clearListeners() }
    }

    override fun onDispose() {
        popup.dispose()
        super.onDispose()
    }

    override fun onAfterRediff() {
        val change = getClosestChange() ?: return
        val changeContent = getChangeContent(change)

        if (normalizeString(getDocument(Side.LEFT).text).contains(normalizeString(changeContent))) {
            popup.dispose()
            return
        }

        myEditor.component.preferredSize =
            Dimension(
                mainEditor.component.width / 2,
                (myEditor.lineHeight * change.getChangedLinesCount())
            )
        adjustPopupSize(popup, myEditor)

        updateFooterComponent()

        val changeOffset = change.lineFragment.startOffset1
        val adjustedLocation =
            getAdjustedPopupLocation(popup, mainEditor, changeOffset)

        if (popup.isVisible) {
            popup.setLocation(adjustedLocation)
        } else {
            popup.showInScreenCoordinates(mainEditor.component, adjustedLocation)
        }

        scrollToChange(change)
    }

    fun applyChanges() {
        val changes = diffChanges ?: emptyList()
        val change = getClosestChange() ?: return

        if (isStateIsOutOfDate) return
        if (!isEditable(masterSide, true)) return

        val document: Document = getDocument(masterSide)

        DiffUtil.executeWriteCommand(document, project, null) {
            applyInProgress = true
            try {
                replaceChange(change, masterSide)
            } finally {
                applyInProgress = false
            }
            moveCaretToChange(change, document)
            scheduleRediff()
        }
        rediff(true)

        if (changes.size == 1) {
            popup.dispose()

            application.executeOnPooledThread {
                NextEditCoordinator.requestNextEdit(
                    mainEditor,
                    mainEditor.document.text,
                    runReadAction { mainEditor.caretModel.offset },
                    false
                )
            }
        }

        application.executeOnPooledThread {
            grpcService?.acceptEdit(UUID.fromString(nextEditResponse.id), change.toString())
        }
    }

    fun isVisible(): Boolean {
        return popup.isVisible
    }

    private fun setupDiffEditor() {
        myEditor.apply {
            settings.apply {
                additionalLinesCount = 0
                isFoldingOutlineShown = false
                isCaretRowShown = false
                isBlinkCaret = false
                isDndEnabled = false
                isIndentGuidesShown = false
            }
            gutterComponentEx.isVisible = false
            gutterComponentEx.parent.isVisible = false
            scrollPane.horizontalScrollBar.isOpaque = false
        }
    }

    private fun clearListeners() {
        mainEditor.putUserData(CodeGPTKeys.EDITOR_PREDICTION_DIFF_VIEWER, null)
        mainEditor.scrollingModel.removeVisibleAreaListener(visibleAreaListener)
        mainEditor.document.removeDocumentListener(documentListener)
    }

    private fun normalizeString(text: String): String {
        return text.replace("\\s+".toRegex(), "").lowercase()
    }

    private fun getChangeContent(change: UnifiedDiffChange): String {
        val startOffset = change.lineFragment.startOffset2
        val endOffset = change.lineFragment.endOffset2
        return getDocument(Side.RIGHT).getText(TextRange(startOffset, endOffset))
    }

    private fun getClosestChange(): UnifiedDiffChange? {
        val changes = diffChanges ?: emptyList()
        val cursorOffset = mainEditor.caretModel.offset
        return changes.minByOrNull { abs(it.lineFragment.startOffset1 - cursorOffset) }
    }

    private fun getVisibleAreaListener(): VisibleAreaListener {
        return object : VisibleAreaListener {
            override fun visibleAreaChanged(event: VisibleAreaEvent) {
                val change = getClosestChange() ?: return
                val adjustedLocation = getAdjustedPopupLocation(
                    popup,
                    mainEditor,
                    change.lineFragment.startOffset1
                )

                if (popup.isVisible && !popup.isDisposed) {
                    popup.setLocation(adjustedLocation)
                    adjustPopupSize(popup, myEditor)
                }
            }
        }
    }

    private fun getDocumentListener(): DocumentListener {
        return object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (applyInProgress) return

                popup.setUiVisible(false)
                onDispose()
            }
        }
    }

    private fun scrollToChange(change: UnifiedDiffChange) {
        val pointToScroll = myEditor.logicalPositionToXY(LogicalPosition(change.line1, 0))
        pointToScroll.y -= myEditor.lineHeight
        DiffUtil.scrollToPoint(myEditor, pointToScroll, false)
    }

    private fun moveCaretToChange(change: UnifiedDiffChange, document: Document) {
        val changeEndOffset = change.lineFragment.endOffset2
        val previousChar = document.getText(TextRange(changeEndOffset - 1, changeEndOffset))
        val offset = if (previousChar == "\n") changeEndOffset - 1 else changeEndOffset

        mainEditor.caretModel.moveToOffset(max(offset, 0))

        val offsetPosition = mainEditor.offsetToXY(offset)
        val offsetVisible = mainEditor.scrollingModel.visibleArea.contains(offsetPosition)
        if (!offsetVisible) {
            DiffUtil.scrollToCaret(mainEditor, false)
        }
    }

    private fun updateFooterComponent() {
        for (component in myEditor.component.components) {
            if (component is BorderLayoutPanel) {
                myEditor.component.remove(component)
            }
        }

        myEditor.component.add(
            BorderLayoutPanel()
                .addToLeft(
                    JBLabel(
                        "Accept: ${getShortcutText(AcceptNextPredictionRevisionAction.ID)} " +
                                "· Trigger: ${getShortcutText(TriggerCustomPredictionAction.ID)} " +
                                "· Open: ${getShortcutText(OpenPredictionAction.ID)} " +
                                "· Changes: ${diffChanges?.size ?: 0}"
                    )
                        .apply {
                            font = JBUI.Fonts.miniFont()
                        })
                .apply {
                    background = editor.backgroundColor
                    border = JBUI.Borders.empty(4)
                },
            BorderLayout.SOUTH
        )

        myEditor.component.revalidate()
        myEditor.component.repaint()
    }

    class MyDiffContext(private val project: Project?) : DiffContext() {
        private val ownContext: UserDataHolder = UserDataHolderBase()

        override fun getProject() = project

        override fun isFocusedInWindow(): Boolean {
            return false
        }

        override fun isWindowFocused(): Boolean {
            return false
        }

        override fun requestFocusInWindow() {
        }

        override fun <T> getUserData(key: Key<T>): T? {
            return ownContext.getUserData(key)
        }

        override fun <T> putUserData(key: Key<T>, value: T?) {
            ownContext.putUserData(key, value)
        }
    }

    companion object {

        @RequiresEdt
        fun displayInlineDiff(
            editor: Editor,
            nextEditResponse: NextEditResponse,
        ) {
            val nextRevision = nextEditResponse.nextRevision
            if (editor.virtualFile == null || editor.isViewer || nextRevision.isEmpty()) {
                return
            }

            editor.getUserData(CodeGPTKeys.EDITOR_PREDICTION_DIFF_VIEWER)?.dispose()
            editor.putUserData(CodeGPTKeys.REMAINING_EDITOR_COMPLETION, null)
            InlineCompletionContext.getOrNull(editor)?.let {
                if (it.isCurrentlyDisplaying()) {
                    InlineCompletionContext.getOrNull(editor)?.clear()
                }
            }

            val diffRequest = createSimpleDiffRequest(editor, nextRevision)
            val diffViewer = CodeSuggestionDiffViewer(diffRequest, nextEditResponse, editor)
            editor.putUserData(CodeGPTKeys.EDITOR_PREDICTION_DIFF_VIEWER, diffViewer)
            diffViewer.rediff(true)
        }
    }
}

fun createSimpleDiffRequest(editor: Editor, nextRevision: String): SimpleDiffRequest {
    val project = editor.project
    val virtualFile = editor.virtualFile
    val tempDiffFile = LightVirtualFile(virtualFile.name, nextRevision)
    val diffContentFactory = DiffContentFactory.getInstance()
    return SimpleDiffRequest(
        null,
        diffContentFactory.create(project, virtualFile),
        diffContentFactory.create(project, tempDiffFile),
        null,
        null
    )
}

fun UnifiedDiffChange.getChangedLinesCount(): Int {
    val insertedLines = insertedRange.end - insertedRange.start
    val deletedLines = deletedRange.end - deletedRange.start
    return deletedLines + insertedLines + 2
}

fun getAdjustedPopupLocation(popup: JBPopup, editor: Editor, changeOffset: Int): Point {
    val pointInEditor = editor.offsetToXY(changeOffset)
    if (!editor.component.isShowing) {
        val point = Point(pointInEditor)
        SwingUtilities.convertPointToScreen(point, editor.component)
        return point
    }

    val visibleArea = editor.scrollingModel.visibleArea
    val editorLocationOnScreen = editor.component.locationOnScreen
    val isOffsetVisible = visibleArea.contains(pointInEditor)
    val popupY = if (isOffsetVisible) {
        editorLocationOnScreen.y + pointInEditor.y - editor.scrollingModel.verticalScrollOffset
    } else {
        if (pointInEditor.y < visibleArea.y) {
            editorLocationOnScreen.y
        } else {
            editorLocationOnScreen.y + visibleArea.height - popup.size.height
        }
    }
    val popupX = editorLocationOnScreen.x + editor.component.width - popup.size.width
    return Point(popupX, popupY - editor.lineHeight)
}

fun adjustPopupSize(popup: JBPopup, editor: Editor) {
    val newWidth = editor.component.preferredSize.width
    val newHeight = editor.component.preferredSize.height
    popup.size = Dimension(newWidth, newHeight)
    popup.content.revalidate()
    popup.content.repaint()
}

fun getShortcutText(actionId: String): String {
    return KeymapUtil.getFirstKeyboardShortcutText(
        ActionManager.getInstance().getAction(actionId)
    )
}

fun createSuggestionDiffPopup(content: JComponent): JBPopup {
    return JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
        .setNormalWindowLevel(true)
        .setCancelOnClickOutside(false)
        .setRequestFocus(false)
        .setFocusable(true)
        .setMovable(true)
        .setResizable(true)
        .setShowBorder(true)
        .setCancelKeyEnabled(true)
        .setCancelOnWindowDeactivation(false)
        .setCancelOnOtherWindowOpen(false)
        .createPopup()
}
