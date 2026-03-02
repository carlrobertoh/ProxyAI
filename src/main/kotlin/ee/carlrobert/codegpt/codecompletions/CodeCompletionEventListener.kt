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
import ee.carlrobert.codegpt.completions.CompletionError
import ee.carlrobert.codegpt.completions.CompletionStreamEventListener
import ee.carlrobert.codegpt.nextedit.NextEditCoordinator
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.ui.OverlayUtil.showNotification
import kotlinx.coroutines.channels.ProducerScope
import java.util.concurrent.atomic.AtomicBoolean

class CodeCompletionEventListener(
    private val editor: Editor,
    private val channel: ProducerScope<InlineCompletionElement>,
) : CompletionStreamEventListener {

    companion object {
        private val logger = thisLogger()
    }

    private val cancelled = AtomicBoolean(false)
    private val messageBuilder = StringBuilder()
    private val cursorOffset = runReadAction { editor.caretModel.offset }
    private val prefix = editor.document.getText(TextRange(0, cursorOffset))
    private val suffix =
        editor.document.getText(TextRange(cursorOffset, editor.document.textLength))
    private val cache = editor.project?.service<CodeCompletionCacheService>()

    override fun onOpen() {
        setLoading(true)
    }

    override fun onMessage(message: String) {
        if (cancelled.get()) {
            return
        }

        messageBuilder.append(message)
    }

    override fun onComplete(messageBuilder: StringBuilder) {
        try {
            CodeGPTKeys.REMAINING_CODE_COMPLETION.set(editor, null)
            CodeGPTKeys.REMAINING_NEXT_EDITS.set(editor, null)

            if (cancelled.get() || messageBuilder.isEmpty()) {
                return
            }

            val finalResult = CodeCompletionFormatter(editor).format(messageBuilder.toString())
            cache?.setCache(prefix, suffix, finalResult)
            runInEdt { channel.trySend(InlineCompletionGrayTextElement(finalResult)) }
        } finally {
            handleCompleted()
        }
    }

    override fun onCancelled(messageBuilder: StringBuilder) {
        cancelled.set(true)
        handleCompleted()
    }

    override fun onError(error: CompletionError, ex: Throwable) {
        val isCodeGPTService =
            service<ModelSettings>().getServiceForFeature(FeatureType.CODE_COMPLETION) == ServiceType.PROXYAI
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
        channel.close()
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
}
