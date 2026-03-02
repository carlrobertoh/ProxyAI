package ee.carlrobert.codegpt.codecompletions

import com.intellij.codeInsight.inline.completion.*
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.CodeGPTKeys.REMAINING_CODE_COMPLETION
import ee.carlrobert.codegpt.completions.CancellableRequest
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.codegpt.settings.service.inception.InceptionSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.mistral.MistralSettings
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class DebouncedCodeCompletionProvider : DebouncedInlineCompletionProvider() {

    private val currentCallRef = AtomicReference<CancellableRequest?>(null)

    override val id: InlineCompletionProviderID
        get() = InlineCompletionProviderID("CodeGPTInlineCompletionProvider")

    override val suggestionUpdateManager: CodeCompletionSuggestionUpdateAdapter
        get() = CodeCompletionSuggestionUpdateAdapter()

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
            editor.project ?: return InlineCompletionSingleSuggestion.build(elements = emptyFlow())

        return InlineCompletionSingleSuggestion.build(elements = channelFlow {
            try {
                currentCallRef.getAndSet(null)?.cancel()
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

                val eventListener = CodeCompletionEventListener(request.editor, this)
                val infillRequest = InfillRequestUtil.buildInfillRequest(request)
                val call = service<CodeCompletionService>().getCodeCompletionAsync(
                    infillRequest,
                    eventListener
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
        val force = request.event is InlineCompletionEvent.DirectCall
        return if (!force) {
            val debounceMs = CompletionTracker.calcDebounceTime(request.editor)
            CompletionTracker.updateLastCompletionRequestTime(request.editor)
            debounceMs.toDuration(DurationUnit.MILLISECONDS)
        } else {
            0.toDuration(DurationUnit.MILLISECONDS)
        }
    }

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val selectedService =
            service<ModelSettings>().getServiceForFeature(FeatureType.CODE_COMPLETION)
        val codeCompletionsEnabled = when (selectedService) {
            ServiceType.PROXYAI -> service<CodeGPTServiceSettings>().state.codeCompletionSettings.codeCompletionsEnabled
            ServiceType.OPENAI -> OpenAISettings.getCurrentState().isCodeCompletionsEnabled
            ServiceType.CUSTOM_OPENAI -> service<CustomServicesSettings>()
                .customServiceStateForFeatureType(FeatureType.CODE_COMPLETION)
                .codeCompletionSettings.codeCompletionsEnabled

            ServiceType.LLAMA_CPP -> LlamaSettings.isCodeCompletionsPossible()
            ServiceType.OLLAMA -> service<OllamaSettings>().state.codeCompletionsEnabled
            ServiceType.MISTRAL -> MistralSettings.getCurrentState().isCodeCompletionsEnabled
            ServiceType.INCEPTION -> service<InceptionSettings>().state.codeCompletionsEnabled
            ServiceType.ANTHROPIC,
            ServiceType.GOOGLE -> false
        }

        if (!codeCompletionsEnabled) {
            return false
        }

        return event is LookupInlineCompletionEvent
                || event is InlineCompletionEvent.DirectCall
                || event is InlineCompletionEvent.DocumentChange
    }
}
