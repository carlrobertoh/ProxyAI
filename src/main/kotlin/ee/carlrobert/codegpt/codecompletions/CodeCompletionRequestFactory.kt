package ee.carlrobert.codegpt.codecompletions

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.completions.llama.LlamaModel
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettingsState
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings
import ee.carlrobert.llm.client.llama.completion.LlamaCompletionRequest
import ee.carlrobert.llm.client.openai.completion.request.OpenAITextCompletionRequest
import okhttp3.Request
import okhttp3.RequestBody
import java.nio.charset.StandardCharsets

object CodeCompletionRequestFactory {

    @JvmStatic
    fun buildOpenAIRequest(details: InfillRequestDetails): OpenAITextCompletionRequest {
        return OpenAITextCompletionRequest.Builder(details.prefix)
            .setSuffix(details.suffix)
            .setStream(true)
            .setMaxTokens(OpenAISettings.getCurrentState().codeCompletionMaxTokens)
            .setTemperature(0.4)
            .build()
    }

    @JvmStatic
    fun buildCustomRequest(details: InfillRequestDetails): Request {
        val settings = service<CustomServiceSettings>().state.codeCompletionSettings
        val requestBuilder = Request.Builder().url(settings.url!!)
        val credential = getCredential(CredentialKey.CUSTOM_SERVICE_API_KEY)
        for (entry in settings.headers.entries) {
            var value = entry.value
            if (credential != null && value.contains("\$CUSTOM_SERVICE_API_KEY")) {
                value = value.replace("\$CUSTOM_SERVICE_API_KEY", credential)
            }
            requestBuilder.addHeader(entry.key, value)
        }
        val transformedBody = settings.body.entries.associate { (key, value) ->
            if (value is String && "\$FIM_PROMPT" == value) {
                key to settings.infillTemplate.buildPrompt(details.prefix, details.suffix)
            } else {
                key to value
            }
        }

        try {
            val requestBody = RequestBody.create(
                null, ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(transformedBody)
                    .toByteArray(StandardCharsets.UTF_8)
            )
            return requestBuilder.post(requestBody).build()
        } catch (e: JsonProcessingException) {
            throw RuntimeException(e)
        }
    }

    @JvmStatic
    fun buildLlamaRequest(details: InfillRequestDetails): LlamaCompletionRequest {
        val settings = LlamaSettings.getCurrentState()
        val promptTemplate = getLlamaInfillPromptTemplate(settings)
        val prompt = promptTemplate.buildPrompt(details.prefix, details.suffix)
        return LlamaCompletionRequest.Builder(prompt)
            .setN_predict(settings.codeCompletionMaxTokens)
            .setStream(true)
            .setTemperature(0.4)
            .setStop(promptTemplate.stopTokens)
            .build()
    }

    private fun getLlamaInfillPromptTemplate(settings: LlamaSettingsState): InfillPromptTemplate {
        if (!settings.isRunLocalServer) {
            return settings.remoteModelInfillPromptTemplate
        }
        if (settings.isUseCustomModel) {
            return settings.localModelInfillPromptTemplate
        }
        return LlamaModel.findByHuggingFaceModel(settings.huggingFaceModel).infillPromptTemplate
    }
}