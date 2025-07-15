package ee.carlrobert.codegpt.codecompletions

import com.intellij.codeInsight.inline.completion.*
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.CodeGPTKeys.REMAINING_CODE_COMPLETION
import ee.carlrobert.codegpt.codecompletions.edit.GrpcClientService
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.sse.EventSource
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class DebouncedCodeCompletionProvider : DebouncedInlineCompletionProvider() {

    private val currentCallRef = AtomicReference<EventSource?>(null)

    override val id: InlineCompletionProviderID
        get() = InlineCompletionProviderID("CodeGPTInlineCompletionProvider")

    override val insertHandler: InlineCompletionInsertHandler
        get() = CodeCompletionInsertHandler()

    override val providerPresentation: InlineCompletionProviderPresentation
        get() = CodeCompletionProviderPresentation()

    override fun shouldBeForced(request: InlineCompletionRequest): Boolean {
        return request.event is InlineCompletionEvent.DirectCall || tryFindCache(request) != null
    }

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val editor = request.editor
        val project =
            editor.project ?: return InlineCompletionSuggestion.Default(emptyFlow())

        if (LookupManager.getActiveLookup(request.editor) != null) {
            return InlineCompletionSuggestion.Default(emptyFlow())
        }

        return InlineCompletionSuggestion.Default(channelFlow {
            try {
                val remainingCodeCompletion = REMAINING_CODE_COMPLETION.get(editor)
                if (remainingCodeCompletion != null && request.event is InlineCompletionEvent.DirectCall) {
                    REMAINING_CODE_COMPLETION.set(editor, null)
                    trySend(InlineCompletionGrayTextElement(remainingCodeCompletion.partialCompletion))
                    return@channelFlow
                }

                val cacheValue = tryFindCache(request)
                if (cacheValue != null) {
                    REMAINING_CODE_COMPLETION.set(editor, null)
                    trySend(InlineCompletionGrayTextElement(cacheValue))
                    return@channelFlow
                }

                CompletionProgressNotifier.update(project, true)

                var eventListener = CodeCompletionEventListener(request.editor, this)

                if (service<ModelSelectionService>().getServiceForFeature(FeatureType.CODE_COMPLETION) == ServiceType.PROXYAI) {
                    project.service<GrpcClientService>()
                        .getCodeCompletionAsync(eventListener, request, this)
                    return@channelFlow
                }

                val infillRequest = InfillRequestUtil.buildInfillRequest(request)
                val call = project.service<CodeCompletionService>().getCodeCompletionAsync(
                    infillRequest,
                    CodeCompletionEventListener(request.editor, this)
                )

                currentCallRef.set(call)
            } finally {
                awaitClose { currentCallRef.getAndSet(null)?.cancel() }
            }
        })
    }

    private fun tryFindCache(request: InlineCompletionRequest): String? {
        val editor = request.editor
        val project = editor.project ?: return null
        return project.service<CodeCompletionCacheService>().getCache(editor)
    }

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration {
        return 300.toDuration(DurationUnit.MILLISECONDS)
    }

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val selectedService =
            service<ModelSelectionService>().getServiceForFeature(FeatureType.CODE_COMPLETION)
        val codeCompletionsEnabled = when (selectedService) {
            ServiceType.PROXYAI -> service<CodeGPTServiceSettings>().state.codeCompletionSettings.codeCompletionsEnabled
            ServiceType.OPENAI -> OpenAISettings.getCurrentState().isCodeCompletionsEnabled
            ServiceType.CUSTOM_OPENAI -> service<CustomServicesSettings>().state.active.codeCompletionSettings.codeCompletionsEnabled
            ServiceType.LLAMA_CPP -> LlamaSettings.isCodeCompletionsPossible()
            ServiceType.OLLAMA -> service<OllamaSettings>().state.codeCompletionsEnabled
            ServiceType.ANTHROPIC,
            ServiceType.GOOGLE,
            null -> false
        }

        if (event is LookupInlineCompletionEvent) {
            return true
        }

        val hasActiveCompletion =
            REMAINING_CODE_COMPLETION.get(event.toRequest()?.editor)?.partialCompletion?.isNotEmpty() == true

        if (!codeCompletionsEnabled) {
            return event is InlineCompletionEvent.DocumentChange
                    && selectedService == ServiceType.PROXYAI
                    && service<CodeGPTServiceSettings>().state.nextEditsEnabled
                    && !hasActiveCompletion
        }

        return event is InlineCompletionEvent.DocumentChange || hasActiveCompletion
    }
}