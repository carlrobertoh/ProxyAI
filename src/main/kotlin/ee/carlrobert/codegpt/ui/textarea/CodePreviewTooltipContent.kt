package ee.carlrobert.codegpt.ui.textarea

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.util.EditorUtil.reformatDocument
import java.awt.BorderLayout
import java.awt.Dimension
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.JPanel

class CodePreviewTooltipContent(
    private val project: Project,
    fileName: String,
    fileContent: String
) : JPanel() {

    private val fileType = service<FileTypeManager>().getFileTypeByFileName(fileName)
    private val editor: EditorEx

    init {
        layout = BorderLayout()
        background = JBColor.background()

        val document = createDocument(fileName, fileContent)
        editor = createEditor(document)

        add(editor.component, BorderLayout.CENTER)

        // TODO
        val minHeight = 80
        val contentHeight = calculateContentHeight(editor) + 56 // Popup header height?
        val editorHeight = maxOf(minHeight, contentHeight)

        preferredSize = Dimension(400, editorHeight)
    }

    fun getFocusableComponent(): JComponent {
        return editor.component
    }

    private fun createEditor(document: Document): EditorEx {
        val editor =
            (service<EditorFactory>()
                .createEditor(document, project, fileType, true) as EditorEx)
                .apply {
                    colorsScheme = EditorColorsManager.getInstance().schemeForCurrentUITheme
                    setVerticalScrollbarVisible(true)
                    setHorizontalScrollbarVisible(true)
                    settings.apply {
                        isLineNumbersShown = false
                        isLineMarkerAreaShown = false
                        isFoldingOutlineShown = false
                        additionalColumnsCount = 0
                        additionalLinesCount = 0
                        isRightMarginShown = false
                        isShowIntentionBulb = false
                        setGutterIconsShown(false)
                    }
                }

        if (service<ConfigurationSettings>().state.autoFormattingEnabled) {
            runUndoTransparentWriteAction {
                reformatDocument(project, document, 0, document.textLength)
            }
        }

        return editor
    }

    private fun calculateContentHeight(editor: EditorEx): Int {
        return editor.lineHeight * editor.document.lineCount
    }

    fun dispose() {
        service<EditorFactory>().releaseEditor(editor)
    }

    private fun createDocument(fileName: String, fileContent: String): Document {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now())
        val lightVirtualFile = LightVirtualFile(
            "${PathManager.getTempPath()}/${timestamp}_$fileName",
            fileContent
        )
        val existingDocument = service<FileDocumentManager>().getDocument(lightVirtualFile)
        return existingDocument
            ?: service<EditorFactory>().createDocument(StringUtil.convertLineSeparators(fileContent))
    }
}
