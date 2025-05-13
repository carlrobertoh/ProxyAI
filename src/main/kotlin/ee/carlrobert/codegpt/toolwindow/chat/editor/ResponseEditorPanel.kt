package ee.carlrobert.codegpt.toolwindow.chat.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.BulkAwareDocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.ContextMenuPopupHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.actions.toolwindow.ReplaceCodeInMainEditorAction
import ee.carlrobert.codegpt.toolwindow.chat.parser.StreamParseResponse
import ee.carlrobert.codegpt.util.EditorUtil
import ee.carlrobert.codegpt.util.file.FileUtil.findLanguageExtensionMapping
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

class ResponseEditorPanel(
    project: Project,
    item: StreamParseResponse,
    readOnly: Boolean,
    disposableParent: Disposable
) : JPanel(BorderLayout()), Disposable {

    companion object {
        private val EXPANDED_KEY = Key.create<Boolean>("toolwindow.editor.isExpanded")
        private const val MAX_VISIBLE_LINES = 10
        private const val MIN_LINES_FOR_EXPAND = 8
    }

    val editor: Editor
    private val expandLinkPanel: JPanel
    private var expandLinkAdded = false

    init {
        border = JBUI.Borders.empty(8, 0)
        isOpaque = false

        val languageMapping = findLanguageExtensionMapping(item.language)
        editor = EditorUtil.createEditor(
            project,
            languageMapping.value,
            StringUtil.convertLineSeparators(item.content)
        )

        val group = DefaultActionGroup().apply {
            add(ReplaceCodeInMainEditorAction())

            (editor as EditorEx).contextMenuGroupId?.let { groupId ->
                val actionManager = ActionManager.getInstance()
                val originalGroup = actionManager.getAction(groupId)
                if (originalGroup is ActionGroup) {
                    addAll(originalGroup.getChildren(null, actionManager).toList())
                }
            }
        }

        configureEditor(
            project,
            editor as EditorEx,
            readOnly,
            ContextMenuPopupHandler.Simple(group),
            item.filePath ?: "",
            languageMapping.key
        )

        add(editor.component, BorderLayout.CENTER)

        expandLinkPanel = JPanel(FlowLayout(FlowLayout.CENTER)).apply {
            isOpaque = false
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(ColorUtil.fromHex("#48494b"), 0, 1, 1, 1),
                JBUI.Borders.empty(0)
            )
            add(createLink(editor))
        }

        editor.document.addDocumentListener(object : BulkAwareDocumentListener.Simple {
            override fun documentChanged(event: DocumentEvent) {
                updateEditorHeightAndUI()
                scrollToEnd()
            }
        })

        if (editor.document.text.lines().size >= MIN_LINES_FOR_EXPAND) {
            updateEditorHeightAndUI()
        }
        Disposer.register(disposableParent, this)
    }

    override fun dispose() {
        EditorFactory.getInstance().releaseEditor(editor)
    }

    private fun configureEditor(
        project: Project,
        editorEx: EditorEx,
        readOnly: Boolean,
        popupHandler: ContextMenuPopupHandler,
        filePath: String,
        language: String
    ) {
        EXPANDED_KEY.set(editorEx, false)

        editorEx.installPopupHandler(popupHandler)
        editorEx.colorsScheme = EditorColorsManager.getInstance().schemeForCurrentUITheme

        editorEx.settings.apply {
            additionalColumnsCount = 0
            additionalLinesCount = 0
            isAdditionalPageAtBottom = false
            isVirtualSpace = false
            isUseSoftWraps = false
            isLineMarkerAreaShown = false
            isLineNumbersShown = false
        }

        editorEx.gutterComponentEx.apply {
            isVisible = true
            parent.isVisible = false
        }

        editorEx.contentComponent.border = JBUI.Borders.emptyLeft(4)
        editorEx.setBorder(IdeBorderFactory.createBorder(ColorUtil.fromHex("#48494b")))
        editorEx.permanentHeaderComponent =
            HeaderPanel(project, editorEx, filePath, language, readOnly)
        editorEx.headerComponent = null
    }

    private fun getLinkText(expanded: Boolean): String {
        return if (expanded) {
            CodeGPTBundle.get("toolwindow.chat.editor.action.collapse")
        } else {
            CodeGPTBundle.get("toolwindow.chat.editor.action.expand")
        }
    }

    private fun createLink(editorEx: EditorEx): ActionLink {
        val isExpanded = EXPANDED_KEY.get(editorEx) ?: false
        val linkText = getLinkText(isExpanded)

        return ActionLink(linkText) { event ->
            val currentState = EXPANDED_KEY.get(editorEx) ?: false
            val newState = !currentState
            EXPANDED_KEY.set(editorEx, newState)

            val source = event.source as ActionLink
            source.text = getLinkText(newState)
            source.icon = if (newState) Icons.CollapseAll else Icons.ExpandAll

            if (newState) {
                editorEx.component.preferredSize = null
            } else {
                updateEditorHeightAndUI()
            }

            editorEx.component.revalidate()
            editorEx.component.repaint()
        }.apply {
            icon = if (isExpanded) Icons.CollapseAll else Icons.ExpandAll
            font = JBUI.Fonts.smallFont()
            foreground = JBColor.GRAY
            horizontalAlignment = SwingConstants.CENTER
        }
    }

    private fun updateEditorHeightAndUI() {
        (editor as? EditorEx)?.let { editorEx ->
            val lineHeight = editorEx.lineHeight
            val lineCount = editor.document.lineCount
            val isExpanded = EXPANDED_KEY.get(editorEx) ?: false

            if (lineCount > MIN_LINES_FOR_EXPAND && !expandLinkAdded) {
                add(expandLinkPanel, BorderLayout.SOUTH)
                expandLinkAdded = true
                revalidate()
                repaint()
            }

            if (lineCount <= MIN_LINES_FOR_EXPAND) {
                return
            }

            if (!isExpanded) {
                val visibleLines = lineCount.coerceAtMost(MAX_VISIBLE_LINES)
                val desiredHeight = (lineHeight * visibleLines).coerceAtLeast(20)

                editor.component.preferredSize = Dimension(editor.component.width, desiredHeight)
            }

            editor.component.revalidate()
            editor.component.repaint()
        }
    }

    private fun scrollToEnd() {
        val textLength = editor.document.textLength
        if (textLength > 0) {
            val logicalPosition = editor.offsetToLogicalPosition(textLength - 1)
            editor.caretModel.moveToOffset(textLength - 1)
            editor.scrollingModel.scrollTo(
                LogicalPosition(logicalPosition.line, 0),
                ScrollType.MAKE_VISIBLE
            )
        }
    }
}