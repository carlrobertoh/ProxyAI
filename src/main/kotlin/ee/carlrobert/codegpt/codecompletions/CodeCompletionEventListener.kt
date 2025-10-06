package ee.carlrobert.codegpt.codecompletions

import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.codecompletions.edit.NextEditCoordinator
import ee.carlrobert.codegpt.codecompletions.edit.GrpcClientService
import ee.carlrobert.codegpt.predictions.CodeSuggestionDiffViewer
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.ServiceType.INCEPTION
import ee.carlrobert.codegpt.settings.service.ServiceType.PROXYAI
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.treesitter.CodeCompletionParserFactory
import ee.carlrobert.codegpt.ui.OverlayUtil.showNotification
import ee.carlrobert.llm.client.openai.completion.ErrorDetails
import ee.carlrobert.llm.completion.CompletionEventListener
import ee.carlrobert.service.PartialCodeCompletionResponse
import kotlinx.coroutines.channels.ProducerScope
import okhttp3.sse.EventSource
import java.util.concurrent.atomic.AtomicBoolean

class CodeCompletionEventListener(
    private val editor: Editor,
    private val channel: ProducerScope<InlineCompletionElement>,
) : CompletionEventListener<String> {

    companion object {
        private val logger = thisLogger()
    }

    private val cancelled = AtomicBoolean(false)
    private val messageBuilder = StringBuilder()
    private var firstLine: String? = null
    private val firstLineSent = AtomicBoolean(false)
    private val cursorOffset = runReadAction { editor.caretModel.offset }
    private val prefix = editor.document.getText(TextRange(0, cursorOffset))
    private val suffix =
        editor.document.getText(TextRange(cursorOffset, editor.document.textLength))
    private val cache = editor.project?.service<CodeCompletionCacheService>()

    override fun onOpen() {
        setLoading(true)
    }

    override fun onMessage(message: String, eventSource: EventSource) {
        if (cancelled.get()) {
            return
        }

        messageBuilder.append(message)

        trySendFirstLine(eventSource)
    }

    fun isNotAllowed(completion: String): Boolean {
        if (completion.contains("No newline at end of file")) {
            return true
        } else if (completion.trim().startsWith("+")) {
            return true
        }
        return false
    }

    private fun extractUpToRelevantNewline(message: String): String? {
        if (message.isEmpty()) return null
        val firstNewline = message.indexOf('\n')
        if (firstNewline == -1) return null
        return if (firstNewline == 0) {
            val secondNewline = message.indexOf('\n', 1)
            if (secondNewline != -1) {
                message.substring(0, secondNewline)
            } else {
                message
            }
        } else {
            message.substring(0, firstNewline)
        }
    }

    private fun trySendFirstLine(eventSource: EventSource) {
        if (firstLine != null) {
            return
        }

        var newLine = extractUpToRelevantNewline(messageBuilder.toString())
        if (newLine != null && !firstLineSent.get()) {
            val formattedLine = CodeCompletionFormatter(editor).format(newLine)

            if (isNotAllowed(formattedLine)) {
                cancelled.set(true)
                eventSource.cancel()
                return
            }

            runInEdt {
                channel.trySend(InlineCompletionGrayTextElement(formattedLine))
            }
            firstLineSent.set(true)
            firstLine = newLine
        }
    }

    override fun onComplete(finalResult: StringBuilder) {
        try {
            CodeGPTKeys.REMAINING_CODE_COMPLETION.set(editor, null)
            CodeGPTKeys.REMAINING_PREDICTION_RESPONSE.set(editor, null)

            if (cancelled.get() || finalResult.isEmpty()) {
                return
            }

            if (firstLineSent.get() && firstLine != null) {
                val first = firstLine ?: return
                val remainingContent = finalResult.removePrefix(first).toString()
                if (remainingContent.trim().isEmpty()) {
                    return
                }

                val parsedContent = parseOutput(first + remainingContent)
                if (parsedContent.isNotEmpty()) {
                    cache?.setCache(prefix, suffix, parsedContent)

                    CodeGPTKeys.REMAINING_CODE_COMPLETION.set(
                        editor,
                        PartialCodeCompletionResponse.newBuilder()
                            .setPartialCompletion(parsedContent.removePrefix(first))
                            .build()
                    )
                }
            } else {
                val formattedLine = CodeCompletionFormatter(editor).format(finalResult.toString())
                if (isNotAllowed(formattedLine)) {
                    return
                }

                val parsedContent = parseOutput(formattedLine)
                if (parsedContent.isNotEmpty()) {
                    cache?.setCache(prefix, suffix, parsedContent)
                    runInEdt {
                        channel.trySend(InlineCompletionGrayTextElement(parsedContent))
                    }
                }
            }
        } finally {
            handleCompleted()
        }
    }

    override fun onCancelled(messageBuilder: StringBuilder) {
        cancelled.set(true)
        handleCompleted()
    }

    override fun onError(error: ErrorDetails, ex: Throwable) {
        val isCodeGPTService =
            service<ModelSelectionService>().getServiceForFeature(FeatureType.CODE_COMPLETION) == ServiceType.PROXYAI
        if (isCodeGPTService && "RATE_LIMIT_EXCEEDED" == error.code) {
            service<CodeGPTServiceSettings>().state
                .codeCompletionSettings
                .codeCompletionsEnabled = false
        }

        if (ex.message == null || (ex.message != null && ex.message != "Canceled")) {
            showNotification(error.message, NotificationType.ERROR)
            logger.error(error.message, ex)
        }

        setLoading(false)
    }

    private fun handleCompleted() {
        setLoading(false)

        if (messageBuilder.isEmpty()) {
            NextEditCoordinator.requestNextEdit(
                editor,
                prefix + suffix,
                runReadAction { editor.caretModel.offset },
                false
            )
        }
    }

    private fun setLoading(loading: Boolean) {
        editor.project?.let {
            CompletionProgressNotifier.update(it, loading)
        }
    }

    private fun parseOutput(input: String): String {
        if (!service<ConfigurationSettings>().state.codeCompletionSettings.treeSitterProcessingEnabled) {
            return input
        }

        return CodeCompletionParserFactory
            .getParserForFileExtension(editor.virtualFile.extension)
            .parse(prefix, suffix, input)
            .trimEnd()
    }
}
