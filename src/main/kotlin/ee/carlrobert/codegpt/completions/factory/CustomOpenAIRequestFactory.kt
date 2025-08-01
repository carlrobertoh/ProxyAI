package ee.carlrobert.codegpt.completions.factory

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.completions.BaseRequestFactory
import ee.carlrobert.codegpt.completions.ChatCompletionParameters
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceChatCompletionSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionMessage
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionStandardMessage
import ee.carlrobert.llm.completion.CompletionRequest
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets

class CustomOpenAIRequest(val request: Request) : CompletionRequest

class CustomOpenAIRequestFactory : BaseRequestFactory() {

    override fun createChatRequest(params: ChatCompletionParameters): CustomOpenAIRequest {
        val service = service<CustomServicesSettings>().customServiceStateForFeatureType(FeatureType.CHAT)
        val request = buildCustomOpenAIChatCompletionRequest(
            service.chatCompletionSettings,
            OpenAIRequestFactory.buildOpenAIMessages(
                null,
                params,
                params.referencedFiles,
                params.history,
                params.psiStructure
            ),
            true,
            getCredential(CredentialKey.CustomServiceApiKey(service.name.orEmpty()))
        )
        return CustomOpenAIRequest(request)
    }

    override fun createBasicCompletionRequest(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        stream: Boolean,
        featureType: FeatureType
    ): CompletionRequest {
        val service = service<CustomServicesSettings>().customServiceStateForFeatureType(featureType)

        val request = buildCustomOpenAIChatCompletionRequest(
            service.chatCompletionSettings,
            listOf(
                OpenAIChatCompletionStandardMessage("system", systemPrompt),
                OpenAIChatCompletionStandardMessage("user", userPrompt)
            ),
            stream,
            getCredential(CredentialKey.CustomServiceApiKey(service.name.orEmpty()))
        )
        return CustomOpenAIRequest(request)
    }

    companion object {
        fun buildCustomOpenAICompletionRequest(
            context: String,
            url: String,
            headers: MutableMap<String, String>,
            body: MutableMap<String, Any>,
            credential: String?
        ): Request {
            val usedSettings = CustomServiceChatCompletionSettingsState()
            usedSettings.body = body
            usedSettings.headers = headers
            usedSettings.url = url
            return buildCustomOpenAIChatCompletionRequest(
                usedSettings,
                listOf(OpenAIChatCompletionStandardMessage("user", context)),
                true,
                credential
            )
        }

        fun buildCustomOpenAIChatCompletionRequest(
            settings: CustomServiceChatCompletionSettingsState,
            messages: List<OpenAIChatCompletionMessage>,
            streamRequest: Boolean,
            credential: String?
        ): Request {
            val requestBuilder = Request.Builder().url(requireNotNull(settings.url).trim())

            settings.headers.forEach { (key, value) ->
                val headerValue = when {
                    credential != null && value.contains("\$CUSTOM_SERVICE_API_KEY") ->
                        value.replace("\$CUSTOM_SERVICE_API_KEY", credential)

                    else -> value
                }
                requestBuilder.addHeader(key, headerValue)
            }

            val body = settings.body.mapValues { (key, value) ->
                when {
                    !streamRequest && key == "stream" -> false
                    value is String && value.trim() == "\$OPENAI_MESSAGES" -> messages
                    else -> value
                }
            }

            return try {
                val requestBody = ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(body)
                    .toByteArray(StandardCharsets.UTF_8)
                    .toRequestBody()
                requestBuilder.post(requestBody).build()
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
    }
}
