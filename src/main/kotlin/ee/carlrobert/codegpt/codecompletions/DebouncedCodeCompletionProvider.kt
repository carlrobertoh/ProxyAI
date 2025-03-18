package ee.carlrobert.codegpt.codecompletions

import com.intellij.codeInsight.inline.completion.*
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import ee.carlrobert.codegpt.CodeGPTKeys.REMAINING_EDITOR_COMPLETION
import ee.carlrobert.codegpt.codecompletions.edit.GrpcClientService
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings
import ee.carlrobert.codegpt.util.StringUtil.extractUntilNewline
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class DebouncedCodeCompletionProvider : DebouncedInlineCompletionProvider() {

    companion object {
        private val logger = thisLogger()
    }

    private val currentCallRef = AtomicReference<EventSource?>(null)

    override val id: InlineCompletionProviderID
        get() = InlineCompletionProviderID("CodeGPTInlineCompletionProvider")

    override val insertHandler: InlineCompletionInsertHandler
        get() = CodeCompletionInsertHandler()

    override val providerPresentation: InlineCompletionProviderPresentation
        get() = CodeCompletionProviderPresentation()

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        if (GeneralSettings.getSelectedService() == ServiceType.CODEGPT
            && service<CodeGPTServiceSettings>().state.nextEditsEnabled
        ) {
            predictNextEdit(request)
            return InlineCompletionSingleSuggestion.build(elements = emptyFlow())
        }

        return if (service<ConfigurationSettings>().state.codeCompletionSettings.multiLineEnabled) {
            getMultiLineSuggestionDebounced(request)
        } else {
            getSingleLineSuggestionDebounced(request)
        }
    }

    private fun predictNextEdit(request: InlineCompletionRequest) {
        val project = request.editor.project ?: return
        try {
            CompletionProgressNotifier.update(project, true)
            project.service<GrpcClientService>().getNextEdit(request.editor)
        } catch (ex: Exception) {
            logger.error("Error communicating with server: ${ex.message}")
        } finally {
            CompletionProgressNotifier.update(project, false)
        }
    }

    private fun getSingleLineSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val editor = request.editor
        val remainingCompletion = REMAINING_EDITOR_COMPLETION.get(editor) ?: ""
        if (request.event is InlineCompletionEvent.DirectCall && remainingCompletion.isNotEmpty()
        ) {
            return sendNextSuggestion(remainingCompletion.extractUntilNewline(), request)
        }

        return getSuggestionDebounced(
            request,
            CompletionType.SINGLE_LINE
        ) { project, infillRequest ->
            project.service<CodeCompletionService>()
                .getCodeCompletionAsync(
                    infillRequest,
                    CodeCompletionSingleLineEventListener(request.editor, infillRequest) {
                        trySend(it)
                    }
                )
        }
    }

    private fun getMultiLineSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        return getSuggestionDebounced(
            request,
            CompletionType.MULTI_LINE
        ) { project, infillRequest ->
            project.service<CodeCompletionService>()
                .getCodeCompletionAsync(
                    infillRequest,
                    CodeCompletionMultiLineEventListener(request) {
                        if (it.isEmpty() && service<CodeGPTServiceSettings>().state.nextEditsEnabled) {
                            predictNextEdit(request)
                        } else {
                            trySend(InlineCompletionGrayTextElement(it))
                        }
                    }
                )
        }
    }

    private fun getSuggestionDebounced(
        request: InlineCompletionRequest,
        completionType: CompletionType,
        fetchCompletion: ProducerScope<InlineCompletionElement>.(Project, InfillRequest) -> EventSource
    ): InlineCompletionSuggestion {
        val project = request.editor.project
        if (project == null) {
            logger.error("Could not find project")
            return InlineCompletionSuggestion.Default(emptyFlow())
        }

        if (LookupManager.getActiveLookup(request.editor) != null) {
            return InlineCompletionSuggestion.Default(emptyFlow())
        }

        request.editor.project?.let {
            CompletionProgressNotifier.update(it, true)
        }

        return InlineCompletionSuggestion.Default(channelFlow {
            val infillRequest = InfillRequestUtil.buildInfillRequest(request, completionType)
            currentCallRef.set(fetchCompletion(project, infillRequest))
            awaitClose { currentCallRef.getAndSet(null)?.cancel() }
        })
    }

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration {
        return 600.toDuration(DurationUnit.MILLISECONDS)
    }

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (LookupManager.getActiveLookup(event.toRequest()?.editor) != null) {
            return false
        }

        val selectedService = GeneralSettings.getSelectedService()
        val codeCompletionsEnabled = when (selectedService) {
            ServiceType.CODEGPT -> service<CodeGPTServiceSettings>().state.codeCompletionSettings.codeCompletionsEnabled
            ServiceType.OPENAI -> OpenAISettings.getCurrentState().isCodeCompletionsEnabled
            ServiceType.CUSTOM_OPENAI -> service<CustomServicesSettings>().state.active.codeCompletionSettings.codeCompletionsEnabled
            ServiceType.LLAMA_CPP -> LlamaSettings.isCodeCompletionsPossible()
            ServiceType.OLLAMA -> service<OllamaSettings>().state.codeCompletionsEnabled
            ServiceType.ANTHROPIC,
            ServiceType.AZURE,
            ServiceType.GOOGLE,
            null -> false
        }
        val hasActiveCompletion =
            REMAINING_EDITOR_COMPLETION.get(event.toRequest()?.editor)?.isNotEmpty() ?: false

        if (!codeCompletionsEnabled) {
            return selectedService == ServiceType.CODEGPT
                    && service<CodeGPTServiceSettings>().state.nextEditsEnabled
                    && !hasActiveCompletion
        }

        return event is InlineCompletionEvent.DocumentChange || hasActiveCompletion
    }

    private fun sendNextSuggestion(
        nextCompletion: String,
        request: InlineCompletionRequest
    ): InlineCompletionSuggestion {
        return InlineCompletionSuggestion.Default(channelFlow {
            launch {
                trySend(
                    CodeCompletionTextElement(
                        nextCompletion,
                        request.startOffset,
                        TextRange.from(request.startOffset, nextCompletion.length),
                    )
                )
            }
        })
    }
}
