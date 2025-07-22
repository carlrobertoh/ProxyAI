package ee.carlrobert.codegpt.codecompletions

import ai.grazie.nlp.utils.takeLastWhitespaces
import ai.grazie.nlp.utils.takeWhitespaces
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleManager
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.treesitter.CodeCompletionParserFactory
import kotlin.math.min

object CompletionUtil {

    val logger = thisLogger()

    fun String.formatCompletion(request: InlineCompletionRequest): String {
        try {
            val editor = request.editor
            val project = editor.project ?: return this
            val document = request.document
            val caretOffset = editor.caretModel.offset
            val textBeforeCompletion = document.text.substring(0, caretOffset)
            val textAfterCompletion = document.text.substring(caretOffset)

            val adjustedText = if (
                takeWhitespaces().isNotEmpty()
                && textBeforeCompletion.takeLastWhitespaces().isNotEmpty()
            ) removePrefix(takeWhitespaces())
            else this

            if (adjustedText.lines().size == 1) {
                return adjustedText
            }

            val originalFile = service<FileDocumentManager>().getFile(document) ?: return ""
            val tempFile = project.service<PsiFileFactory>().createFileFromText(
                "temp.${originalFile.extension}",
                originalFile.fileType,
                buildString {
                    append(textBeforeCompletion)
                    append(adjustedText)
                    append(textAfterCompletion)
                }
            )

            project.service<CodeStyleManager>()
                .adjustLineIndent(tempFile, TextRange.from(caretOffset, adjustedText.length))

            val formattedCompletion =
                getFormattedCompletion(adjustedText, tempFile, document, editor)

            val postProcessingEnabled =
                service<ModelSelectionService>().getServiceForFeature(FeatureType.CODE_COMPLETION) != ServiceType.PROXYAI
                        && service<ConfigurationSettings>().state.codeCompletionSettings.treeSitterProcessingEnabled
            return if (postProcessingEnabled) {
                val parser =
                    CodeCompletionParserFactory.getParserForFileExtension(originalFile.extension)
                        ?: return formattedCompletion
                parser
                    .parse(textBeforeCompletion, textAfterCompletion, formattedCompletion)
                    .trimEnd()
            } else {
                formattedCompletion
            }
        } catch (e: Exception) {
            logger.error("Failed to format completion output", e)
            return this
        }
    }

    private fun getFormattedCompletion(
        completionText: String,
        tempFile: PsiFile,
        document: Document,
        editor: Editor,
    ): String {
        val formattedText = StringBuilder()
        val tempFileDocument = tempFile.fileDocument
        val currentCaretLine = editor.caretModel.logicalPosition.line
        var linePosition = currentCaretLine
        for (i in completionText.lines().indices) {
            val minPosition = min(linePosition, tempFileDocument.lineCount - 1)
            val range = TextRange(
                tempFileDocument.getLineStartOffset(minPosition),
                tempFileDocument.getLineEndOffset(minPosition)
            )

            formattedText.append(tempFileDocument.getText(range)).append("\n")
            linePosition++
        }

        val prefixToRemove = document.getText(
            TextRange(document.getLineStartOffset(currentCaretLine), editor.caretModel.offset)
        )
        return formattedText.removePrefix(prefixToRemove).trimEnd().toString()
    }
}
