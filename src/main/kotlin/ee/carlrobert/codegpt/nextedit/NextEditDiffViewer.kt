package ee.carlrobert.codegpt.nextedit

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
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.*
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.application
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.codecompletions.edit.GrpcClientService
import ee.carlrobert.service.NextEditResponse
import kotlinx.coroutines.*
import java.awt.Dimension
import java.awt.Point
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.max

class NextEditDiffViewer(
    request: DiffRequest,
    private val nextEditResponse: NextEditResponse,
    private val mainEditor: Editor,
) : UnifiedDiffViewer(MyDiffContext(mainEditor.project), request), Disposable {

    private val popup: JBPopup = createSuggestionDiffPopup(component)
    private val documentListener: DocumentListener
    private val visibleAreaListener: VisibleAreaListener
    private val caretListener: CaretListener
    private val grpcService = project?.service<GrpcClientService>()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var applyInProgress = false

    init {
        documentListener = getDocumentListener()
        visibleAreaListener = getVisibleAreaListener()
        caretListener = getCaretListener()
        setupDiffEditor()
        mainEditor.document.addDocumentListener(documentListener, this)
        mainEditor.scrollingModel.addVisibleAreaListener(visibleAreaListener, this)
        mainEditor.caretModel.addCaretListener(caretListener, this)

        Disposer.register(popup) { clearListeners() }
    }

    override fun onDispose() {
        applyInProgress = false
        popup.dispose()
        super.onDispose()
    }

    override fun onAfterRediff() {
        applyInProgress = false

        val change = getClosestChange()
        if (change == null) {
            dispose()
            return
        }

        val size = computeCompactSize(change)
        myEditor.component.preferredSize = size
        adjustPopupSize(popup, myEditor, mainEditor)

        val changeOffset = change.lineFragment.startOffset1
        val adjustedLocation = getAdjustedPopupLocation(mainEditor, changeOffset, size)

        if (popup.isVisible) {
            popup.setLocation(adjustedLocation)
        } else {
            popup.showInScreenCoordinates(mainEditor.component, adjustedLocation)
        }
        scrollToChange(change)
    }

    fun applyChanges() {
        if (applyInProgress) return
        applyInProgress = true

        val change = getClosestChange() ?: return
        if (isStateIsOutOfDate) return
        if (!isEditable(masterSide, true)) return

        val document: Document = getDocument(masterSide)
        val leftStart = change.lineFragment.startOffset1
        val leftEnd = change.lineFragment.endOffset1
        val rightStart = change.lineFragment.startOffset2
        val rightEnd = change.lineFragment.endOffset2
        val rightText = safeGetText(getDocument(Side.RIGHT), rightStart, rightEnd)
        val leftText = safeGetText(getDocument(Side.LEFT), leftStart, leftEnd)

        DiffUtil.executeWriteCommand(document, project, null) {
            replaceChange(change, masterSide)
            val caretTarget = if (rightText.contains('\n')) {
                rightStart + rightText.lastIndexOf('\n')
            } else {
                rightEnd
            }
            moveCaretToOffset(caretTarget)
            scheduleRediff()
        }

        application.executeOnPooledThread {
            val cursor = runReadAction { mainEditor.caretModel.offset }
            grpcService?.acceptEdit(nextEditResponse.id, leftText, rightText, cursor)
        }
    }

    fun isVisible(): Boolean = popup.isVisible

    private fun setupDiffEditor() {
        myEditor.apply {
            settings.apply {
                additionalLinesCount = 0
                isFoldingOutlineShown = false
                isCaretRowShown = false
                isBlinkCaret = false
                isDndEnabled = false
                isIndentGuidesShown = false
                isUseSoftWraps = false
            }
            gutterComponentEx.isVisible = false
            gutterComponentEx.parent.isVisible = false
            scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            scrollPane.horizontalScrollBar.isOpaque = false
            scrollPane.verticalScrollBar.isOpaque = false
        }
    }

    private fun clearListeners() {
        mainEditor.putUserData(CodeGPTKeys.EDITOR_PREDICTION_DIFF_VIEWER, null)
        mainEditor.document.removeDocumentListener(documentListener)
        mainEditor.scrollingModel.removeVisibleAreaListener(visibleAreaListener)
        mainEditor.caretModel.removeCaretListener(caretListener)
    }

    private fun safeGetText(document: Document, start: Int, end: Int): String {
        val length = document.textLength
        val safeStart = maxOf(0, minOf(start, length))
        val safeEnd = maxOf(safeStart, minOf(end, length))
        return if (safeStart < length && safeEnd > safeStart) document.getText(
            TextRange(
                safeStart,
                safeEnd
            )
        ) else ""
    }

    private fun getClosestChange(): UnifiedDiffChange? {
        val changes = diffChanges ?: emptyList()
        val filteredChanges = runReadAction {
            changes.filter { change ->
                val leftDoc = getDocument(Side.LEFT)
                val rightDoc = getDocument(Side.RIGHT)
                val leftStart = change.lineFragment.startOffset1
                val leftEnd = change.lineFragment.endOffset1
                val rightStart = change.lineFragment.startOffset2
                val rightEnd = change.lineFragment.endOffset2

                val validLeft =
                    leftStart >= 0 && leftEnd >= leftStart && leftStart < leftDoc.textLength
                val validRight =
                    rightStart >= 0 && rightEnd >= rightStart && rightStart < rightDoc.textLength

                if (!validLeft || !validRight) return@filter false

                val leftText = safeGetText(leftDoc, leftStart, leftEnd)
                val rightText = safeGetText(rightDoc, rightStart, rightEnd)
                val recentCompletion = mainEditor.getUserData(CodeGPTKeys.RECENT_COMPLETION_TEXT)
                if (recentCompletion != null && recentCompletion.isNotEmpty() && leftText.contains(
                        recentCompletion.trim()
                    )
                ) {
                    return@filter false
                }

                rightText.trim().isNotEmpty()
            }
        }

        val cursorOffset = mainEditor.caretModel.offset
        return filteredChanges.minByOrNull { abs(it.lineFragment.startOffset1 - cursorOffset) }
    }

    private fun getDocumentListener(): DocumentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (applyInProgress) return
            popup.setUiVisible(false)
            onDispose()
        }
    }

    private fun getVisibleAreaListener(): VisibleAreaListener = object : VisibleAreaListener {
        override fun visibleAreaChanged(event: VisibleAreaEvent) {
            val change = getClosestChange() ?: return
            if (popup.isDisposed) return

            coroutineScope.launch {
                withContext(Dispatchers.EDT) {
                    adjustPopupSize(popup, myEditor, mainEditor)
                    val adjustedLocation = getAdjustedPopupLocation(
                        mainEditor,
                        change.lineFragment.startOffset1,
                        popup.size
                    )

                    if (popup.isVisible && !popup.isDisposed) {
                        popup.setLocation(adjustedLocation)
                    }
                }
            }
        }
    }

    private fun getCaretListener(): CaretListener = object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) {
            if (applyInProgress) return
            popup.setUiVisible(false)
            onDispose()
        }
    }

    private fun scrollToChange(change: UnifiedDiffChange) {
        val pointToScroll = myEditor.logicalPositionToXY(LogicalPosition(change.line1, 0))
        DiffUtil.scrollToPoint(myEditor, pointToScroll, false)
    }

    private fun moveCaretToOffset(offset: Int) {
        mainEditor.caretModel.moveToOffset(max(offset, 0))
        val offsetPosition = mainEditor.offsetToXY(mainEditor.caretModel.offset)
        val offsetVisible = mainEditor.scrollingModel.visibleArea.contains(offsetPosition)
        if (!offsetVisible) DiffUtil.scrollToCaret(mainEditor, false)
    }

    private fun computeCompactSize(change: UnifiedDiffChange): Dimension {
        val leftText = safeGetText(
            getDocument(Side.LEFT),
            change.lineFragment.startOffset1,
            change.lineFragment.endOffset1
        )
        val rightText = safeGetText(
            getDocument(Side.RIGHT),
            change.lineFragment.startOffset2,
            change.lineFragment.endOffset2
        )

        fun linesOf(s: String): List<String> {
            val cleaned = s.replace("\r", "")
            val parts = cleaned.split('\n')
            return if (cleaned.endsWith('\n') && parts.isNotEmpty()) parts.dropLast(1) else parts
        }

        val leftLines = linesOf(leftText)
        val rightLines = linesOf(rightText)
        val fm =
            myEditor.component.getFontMetrics(myEditor.colorsScheme.getFont(EditorFontType.PLAIN))
        val pad = JBUI.scale(24)
        val maxPx = (leftLines + rightLines).maxOfOrNull { fm.stringWidth(it) } ?: 0
        val widthLimit = maxPx + pad
        val height = change.getChangedLinesCount() * myEditor.lineHeight
        return Dimension(widthLimit, height)
    }

    class MyDiffContext(private val project: Project?) : DiffContext() {
        private val ownContext: UserDataHolder = UserDataHolderBase()
        override fun getProject() = project
        override fun isFocusedInWindow() = false
        override fun isWindowFocused() = false
        override fun requestFocusInWindow() {}
        override fun <T> getUserData(key: Key<T>): T? = ownContext.getUserData(key)
        override fun <T> putUserData(key: Key<T>, value: T?) {
            ownContext.putUserData(key, value)
        }
    }

    companion object {
        @RequiresEdt
        fun displayNextEdit(editor: Editor, nextEditResponse: NextEditResponse) {
            val nextRevision = nextEditResponse.nextRevision
            if (editor.virtualFile == null || editor.isViewer || nextRevision.isEmpty()) return

            editor.getUserData(CodeGPTKeys.EDITOR_PREDICTION_DIFF_VIEWER)?.dispose()
            editor.putUserData(CodeGPTKeys.REMAINING_EDITOR_COMPLETION, null)
            InlineCompletionContext.getOrNull(editor)?.clear()

            val diffRequest = createSimpleDiffRequest(editor, nextRevision)
            val diffViewer = NextEditDiffViewer(diffRequest, nextEditResponse, editor)
            editor.putUserData(CodeGPTKeys.EDITOR_PREDICTION_DIFF_VIEWER, diffViewer)
            diffViewer.rediff(true)
        }
    }
}

fun createSimpleDiffRequest(editor: Editor, nextRevision: String): SimpleDiffRequest {
    val project = editor.project
    val virtualFile = requireNotNull(editor.virtualFile) { "Editor virtual file is required for diff request." }
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
    return deletedLines + insertedLines
}

fun getAdjustedPopupLocation(editor: Editor, changeOffset: Int, popupSize: Dimension): Point {
    val pointInEditor = editor.offsetToXY(changeOffset)
    if (!editor.component.isShowing) {
        val point = Point(pointInEditor)
        SwingUtilities.convertPointToScreen(point, editor.component)
        return point
    }

    val visibleArea = editor.scrollingModel.visibleArea
    val editorLocationOnScreen = editor.component.locationOnScreen
    val verticalOffset = editor.scrollingModel.verticalScrollOffset
    val gutterWidth = (editor as? EditorEx)?.gutterComponentEx?.size?.width ?: 0
    val margin = JBUI.scale(8)

    val yInEditor = pointInEditor.y
    val visibleTopY = visibleArea.y
    val visibleBottomY = visibleArea.y + visibleArea.height
    val changeIsVisible = yInEditor in visibleTopY until visibleBottomY

    if (changeIsVisible) {
        val spaceBelow = visibleBottomY - yInEditor - editor.lineHeight
        val fitsBelow = spaceBelow >= popupSize.height
        if (fitsBelow) {
            val rawLeft = editorLocationOnScreen.x + pointInEditor.x + gutterWidth
            val minLeft = editorLocationOnScreen.x + visibleArea.x + margin
            val maxLeft = editorLocationOnScreen.x + visibleArea.x + visibleArea.width - popupSize.width - margin
            val left = rawLeft.coerceIn(minLeft, maxLeft)
            val top = editorLocationOnScreen.y + yInEditor - verticalOffset
            return Point(left, top)
        }

        val screenChangeY = editorLocationOnScreen.y + yInEditor - verticalOffset
        val spaceAbove = screenChangeY - editorLocationOnScreen.y
        val canPlaceAbove = spaceAbove >= popupSize.height + margin
        val top = if (canPlaceAbove) {
            screenChangeY - popupSize.height - margin
        } else {
            val centered = screenChangeY - popupSize.height / 2
            val minTop = editorLocationOnScreen.y + margin
            val maxTop = editorLocationOnScreen.y + visibleArea.height - popupSize.height - margin
            centered.coerceIn(minTop, maxTop)
        }
        val left = editorLocationOnScreen.x + visibleArea.x + visibleArea.width - popupSize.width - gutterWidth
        return Point(left, top)
    }

    val topAligned = editorLocationOnScreen.y + margin
    val bottomAligned = editorLocationOnScreen.y + visibleArea.height - popupSize.height - margin
    val top = if (yInEditor < visibleTopY) topAligned else bottomAligned
    val left = editorLocationOnScreen.x + visibleArea.x + visibleArea.width - popupSize.width - gutterWidth
    return Point(left, top)
}

fun adjustPopupSize(popup: JBPopup, contentEditor: Editor, anchorEditor: Editor) {
    val preferred = contentEditor.component.preferredSize
    val visibleArea = anchorEditor.scrollingModel.visibleArea
    val margin = JBUI.scale(16)
    val maxWidth = (visibleArea.width - margin).coerceAtLeast(JBUI.scale(64))
    val maxHeight = (visibleArea.height - margin).coerceAtLeast(JBUI.scale(48))
    val newWidth = preferred.width.coerceAtMost(maxWidth)
    val newHeight = preferred.height.coerceAtMost(maxHeight)
    popup.size = Dimension(newWidth, newHeight)
    popup.content.revalidate()
    popup.content.repaint()
}

fun createSuggestionDiffPopup(content: JComponent): JBPopup =
    JBPopupFactory.getInstance().createComponentPopupBuilder(content, null)
        .setNormalWindowLevel(true)
        .setCancelOnClickOutside(false)
        .setRequestFocus(false)
        .setFocusable(true)
        .setMovable(true)
        .setResizable(false)
        .setShowBorder(true)
        .setCancelKeyEnabled(true)
        .setCancelOnWindowDeactivation(false)
        .setCancelOnOtherWindowOpen(false)
        .createPopup()
