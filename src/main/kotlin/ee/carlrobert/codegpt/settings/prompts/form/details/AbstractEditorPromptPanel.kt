package ee.carlrobert.codegpt.settings.prompts.form.details

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import java.awt.Dimension
import javax.swing.JPanel

abstract class AbstractEditorPromptPanel(
    private val details: FormPromptDetails,
    highlightedPlaceholders: List<String> = emptyList()
) : Disposable {
    protected val editor: Editor = createEditor()
    private val highlighterMap = mutableMapOf<String, RangeHighlighter>()

    init {
        highlightedPlaceholders.forEach {
            highlightPlaceholder(it)
        }
    }

    abstract fun getPanel(): JPanel

    private fun createEditor(): Editor {
        return service<EditorFactory>()
            .run {
                createEditor(createDocument(details.instructions ?: ""))
            }
            .apply {
                settings.additionalLinesCount = 0
                settings.isWhitespacesShown = true
                settings.isLineMarkerAreaShown = false
                settings.isIndentGuidesShown = false
                settings.isLineNumbersShown = false
                settings.isFoldingOutlineShown = false
                settings.isUseSoftWraps = true
                settings.isAdditionalPageAtBottom = false
                settings.isVirtualSpace = false
                settings.setSoftMargins(emptyList<Int>())

                component.preferredSize = Dimension(0, lineHeight * 16)

                document.addDocumentListener(object : DocumentListener {
                    override fun documentChanged(event: DocumentEvent) {
                        details.instructions = event.document.text

                        highlighterMap.forEach { (placeholder, highlighter) ->
                            val start = editor.document.text.indexOf(placeholder)
                            if (start == -1) {
                                if (highlighter.isValid) {
                                    highlighter.dispose()
                                }
                            } else if (!highlighter.isValid) {
                                highlighterMap[placeholder] =
                                    createHighlighter(TextRange.from(start, placeholder.length))
                            }
                        }
                    }
                })
            }
    }

    private fun createHighlighter(range: TextRange): RangeHighlighter {
        val attributes = TextAttributes().apply {
            foregroundColor = JBColor(0x00627A, 0xCC7832)
        }

        return editor.markupModel.addRangeHighlighter(
            range.startOffset,
            range.endOffset,
            HighlighterLayer.SELECTION,
            attributes,
            HighlighterTargetArea.EXACT_RANGE
        )
    }

    private fun highlightPlaceholder(placeholder: String) {
        val start = editor.document.text.indexOf(placeholder)
        if (start >= 0) {
            highlighterMap[placeholder] =
                createHighlighter(TextRange.from(start, placeholder.length))
        }
    }

    protected fun updateEditorText(text: String?) {
        runWriteAction {
            editor.document.setText(StringUtil.convertLineSeparators(text ?: ""))
        }
    }

    override fun dispose() {
        EditorFactory.getInstance().releaseEditor(editor)
    }
}
