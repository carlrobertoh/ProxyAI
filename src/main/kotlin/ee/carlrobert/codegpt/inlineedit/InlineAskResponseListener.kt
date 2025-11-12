package ee.carlrobert.codegpt.inlineedit

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.completions.ChatCompletionParameters
import ee.carlrobert.codegpt.completions.CompletionResponseEventListener
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.llm.client.openai.completion.ErrorDetails

class InlineAskResponseListener(
    private val project: Project,
    private val inlay: InlineEditInlay,
) : CompletionResponseEventListener {

    private val logger = Logger.getInstance(InlineAskResponseListener::class.java)
    private val builder = StringBuilder()

    override fun handleRequestOpen() {
        runInEdt { inlay.setThinkingVisible(true) }
        CompletionProgressNotifier.update(project, true)
    }

    override fun handleMessage(message: String) {
        builder.append(message)
        inlay.updateAskResponseStream(message)
        inlay.setAskLastAssistantResponse(builder.toString())
    }

    override fun handleCompleted(fullMessage: String, callParameters: ChatCompletionParameters) {
        try {
            runInEdt {
                inlay.onCompletionFinished()
                inlay.setAskLastAssistantResponse(fullMessage)
                inlay.updateApplyVisibilityAfterComplete(fullMessage)
                CompletionProgressNotifier.update(project, false)
            }
        } catch (e: Exception) {
            logger.warn("Inline Ask completion finalize failed", e)
        }
    }

    override fun handleError(error: ErrorDetails?, ex: Throwable?) {
        runInEdt {
            inlay.onCompletionFinished()
            CompletionProgressNotifier.update(project, false)
            val message = error?.message ?: ex?.message ?: "Something went wrong"
            OverlayUtil.showNotification(message)
        }
    }
}
