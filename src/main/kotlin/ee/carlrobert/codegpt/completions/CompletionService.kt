package ee.carlrobert.codegpt.completions

import ai.koog.prompt.dsl.Prompt
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.completions.CompletionRequestFactory.Companion.getFactory
import ee.carlrobert.codegpt.completions.CompletionRequestService.getCompletion
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.isCredentialSet
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ServiceType
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException

@Service
class CompletionService {

    fun getLookupCompletion(params: LookupCompletionParameters): String {
        return getChatCompletion(FeatureType.LOOKUP) { serviceType ->
            getFactory(serviceType).createLookupPrompt(params)
        }
    }

    fun autoApply(params: AutoApplyParameters): String {
        return getChatCompletion(FeatureType.AUTO_APPLY) { serviceType ->
            getFactory(serviceType).createAutoApplyPrompt(params)
        }
    }

    fun autoApply(
        params: AutoApplyParameters,
        eventListener: CompletionStreamEventListener
    ): CancellableRequest {
        return getChatCompletion(FeatureType.AUTO_APPLY, eventListener) { serviceType ->
            getFactory(serviceType).createAutoApplyPrompt(params)
        }
    }

    fun getCommitMessage(
        params: CommitMessageCompletionParameters,
        eventListener: CompletionStreamEventListener
    ): CancellableRequest {
        return getChatCompletion(FeatureType.COMMIT_MESSAGE, eventListener) { serviceType ->
            getFactory(serviceType).createCommitMessagePrompt(params)
        }
    }

    fun getInlineEditCompletion(
        params: InlineEditCompletionParameters,
        eventListener: CompletionStreamEventListener
    ): CancellableRequest {
        return getChatCompletion(FeatureType.INLINE_EDIT, eventListener) { serviceType ->
            getFactory(serviceType).createInlineEditPrompt(params)
        }
    }

    private fun getChatCompletion(
        featureType: FeatureType,
        prompt: (ServiceType) -> Prompt
    ): String {
        val modelSelection = service<ModelSettings>().getModelSelectionForFeature(featureType)
        return getCompletion(
            modelSelection.provider,
            featureType,
            prompt(modelSelection.provider),
            modelSelection
        )
    }

    private fun getChatCompletion(
        featureType: FeatureType,
        eventListener: CompletionStreamEventListener,
        prompt: (ServiceType) -> Prompt
    ): CancellableRequest {
        val modelSelection = service<ModelSettings>().getModelSelectionForFeature(featureType)
        return CompletionRequestService.getCompletionAsync(
            modelSelection.provider,
            featureType,
            prompt(modelSelection.provider),
            modelSelection,
            eventListener
        )
    }

    companion object {
        @JvmStatic
        fun isRequestAllowed(featureType: FeatureType): Boolean {
            try {
                return ApplicationManager.getApplication()
                    .executeOnPooledThread(Callable {
                        val serviceType = service<ModelSettings>().getServiceForFeature(featureType)
                        isRequestAllowed(serviceType)
                    })
                    .get()
            } catch (e: InterruptedException) {
                throw RuntimeException(e)
            } catch (e: ExecutionException) {
                throw RuntimeException(e)
            }
        }

        @JvmStatic
        fun isRequestAllowed(serviceType: ServiceType): Boolean {
            return when (serviceType) {
                ServiceType.OPENAI -> isCredentialSet(CredentialKey.OpenaiApiKey)
                ServiceType.ANTHROPIC -> isCredentialSet(
                    CredentialKey.AnthropicApiKey
                )

                ServiceType.GOOGLE -> isCredentialSet(CredentialKey.GoogleApiKey)
                ServiceType.MISTRAL -> isCredentialSet(CredentialKey.MistralApiKey)
                ServiceType.INCEPTION -> isCredentialSet(CredentialKey.InceptionApiKey)
                ServiceType.PROXYAI, ServiceType.CUSTOM_OPENAI, ServiceType.LLAMA_CPP, ServiceType.OLLAMA -> true
            }
        }
    }
}
