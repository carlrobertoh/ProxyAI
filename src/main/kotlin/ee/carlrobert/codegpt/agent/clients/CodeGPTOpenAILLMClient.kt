package ee.carlrobert.codegpt.agent.clients

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ee.carlrobert.codegpt.codecompletions.InfillRequest
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders

class CodeGPTOpenAILLMClient(
    private val apiKey: String,
    private val organization: String?,
    private val baseUrl: String,
    private val model: String,
    private val baseClient: HttpClient = HttpClientProvider.createHttpClient()
) : OpenAILLMClient(
    apiKey = apiKey,
    settings = OpenAIClientSettings(baseUrl = baseUrl),
    baseClient = baseClient
),
    CodeCompletionCapable {

    override suspend fun getCodeCompletion(infillRequest: InfillRequest): String {
        val headers = buildMap {
            if (apiKey.isNotBlank()) {
                put(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            put("X-LLM-Application-Tag", "codegpt")
            organization?.takeIf { it.isNotBlank() }?.let {
                put("OpenAI-Organization", it)
            }
        }

        return parseOpenAITextCompletion(
            postCompletionJson(
                client = baseClient,
                url = "$baseUrl/v1/completions",
                headers = headers,
                body = buildOpenAIStyleCompletionBody(model, infillRequest)
            )
        )
    }
}
