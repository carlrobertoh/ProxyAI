package ee.carlrobert.codegpt.agent.clients

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMProvider
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.codecompletions.InfillRequest
import ee.carlrobert.codegpt.completions.inception.InceptionApplyRequest
import ee.carlrobert.codegpt.completions.inception.InceptionChatCompletionResponse
import ee.carlrobert.codegpt.completions.inception.InceptionNextEditRequest
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.util.JsonMapper
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking

class InceptionAILLMClient(
    private val apiKey: String,
    private val settings: OpenAIClientSettings = OpenAIClientSettings(
        baseUrl = System.getProperty(INCEPTION_BASE_URL_PROPERTY) ?: DEFAULT_BASE_URL,
        chatCompletionsPath = DEFAULT_CHAT_COMPLETIONS_PATH
    ),
    private val baseClient: HttpClient = HttpClient()
) : OpenAILLMClient(
    apiKey = apiKey,
    settings = settings,
    baseClient = baseClient
),
    CodeCompletionCapable {
    data object Inception : LLMProvider("inception", "Inception")

    fun getApplyEditCompletion(request: InceptionApplyRequest): InceptionChatCompletionResponse {
        return runBlocking {
            val requestBody = JsonMapper.mapper.writeValueAsString(request)
            val response = baseClient.post("${settings.baseUrl}$APPLY_EDIT_COMPLETION_PATH") {
                contentType(ContentType.Application.Json)
                if (apiKey.isNotBlank()) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                }
                setBody(requestBody)
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw RuntimeException(
                    "Inception apply request failed with status ${response.status.value}: $body"
                )
            }
            JsonMapper.mapper.readValue(body, InceptionChatCompletionResponse::class.java)
        }
    }

    fun getNextEditCompletion(request: InceptionNextEditRequest): InceptionChatCompletionResponse {
        return runBlocking {
            val requestBody = JsonMapper.mapper.writeValueAsString(request)
            val response = baseClient.post("${settings.baseUrl}$NEXT_EDIT_COMPLETION_PATH") {
                contentType(ContentType.Application.Json)
                if (apiKey.isNotBlank()) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                }
                setBody(requestBody)
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw RuntimeException(
                    "Inception next-edit request failed with status ${response.status.value}: $body"
                )
            }
            JsonMapper.mapper.readValue(body, InceptionChatCompletionResponse::class.java)
        }
    }

    override fun llmProvider(): LLMProvider = Inception

    override suspend fun getCodeCompletion(infillRequest: InfillRequest): String {
        val body = JsonMapper.mapper.writeValueAsString(
            mapOf(
                "model" to service<ModelSettings>()
                    .getModelSelectionForFeature(FeatureType.CODE_COMPLETION)
                    .modelId,
                "prompt" to infillRequest.prefix,
                "suffix" to infillRequest.suffix,
                "stream" to false
            )
        )
        val headers = buildMap {
            if (apiKey.isNotBlank()) {
                put(HttpHeaders.Authorization, "Bearer $apiKey")
            }
        }

        return parseOpenAITextCompletion(
            postCompletionJson(
                client = baseClient,
                url = "${settings.baseUrl}/v1/fim/completions",
                headers = headers,
                body = body
            )
        )
    }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.inceptionlabs.ai"
        private const val DEFAULT_CHAT_COMPLETIONS_PATH = "v1/chat/completions"
        private const val APPLY_EDIT_COMPLETION_PATH = "/v1/apply/completions"
        private const val NEXT_EDIT_COMPLETION_PATH = "/v1/edit/completions"
        private const val INCEPTION_BASE_URL_PROPERTY = "inception.baseUrl"
    }
}
