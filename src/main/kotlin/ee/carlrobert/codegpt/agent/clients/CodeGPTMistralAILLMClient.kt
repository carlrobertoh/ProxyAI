package ee.carlrobert.codegpt.agent.clients

import ai.koog.prompt.executor.clients.mistralai.MistralAIClientSettings
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ee.carlrobert.codegpt.codecompletions.InfillRequest
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders

class CodeGPTMistralAILLMClient(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val baseClient: HttpClient = HttpClientProvider.createHttpClient()
) : MistralAILLMClient(
    apiKey = apiKey,
    settings = MistralAIClientSettings(baseUrl = baseUrl),
    baseClient = baseClient
),
    CodeCompletionCapable {

    override suspend fun getCodeCompletion(infillRequest: InfillRequest): String {
        val headers = buildMap {
            if (apiKey.isNotBlank()) {
                put(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            put("X-LLM-Application-Tag", "codegpt")
        }

        return parseOpenAIChatCompletion(
            postCompletionJson(
                client = baseClient,
                url = "$baseUrl/v1/fim/completions",
                headers = headers,
                body = buildOpenAIStyleCompletionBody(model, infillRequest)
            )
        )
    }
}
