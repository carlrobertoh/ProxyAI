package ee.carlrobert.codegpt.agent.clients

import ai.koog.prompt.executor.clients.LLMEmbeddingProvider
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import com.intellij.openapi.components.service
import ee.carlrobert.codegpt.codecompletions.InfillRequest
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettingsState
import ee.carlrobert.codegpt.util.JsonMapper
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders

class CodeGPTOllamaClient private constructor(
    private val delegate: OllamaClient,
    private val settings: OllamaSettingsState,
    private val baseUrl: String,
    private val apiKey: String?,
    private val baseClient: HttpClient
) : LLMClient by delegate,
    LLMEmbeddingProvider by delegate,
    CodeCompletionCapable {

    constructor(
        settings: OllamaSettingsState,
        baseUrl: String,
        apiKey: String?,
        baseClient: HttpClient = HttpClientProvider.createHttpClient()
    ) : this(
        delegate = OllamaClient(baseUrl = baseUrl, baseClient = baseClient),
        settings = settings,
        baseUrl = baseUrl,
        apiKey = apiKey,
        baseClient = baseClient
    )

    override suspend fun getCodeCompletion(infillRequest: InfillRequest): String {
        val model = service<ModelSettings>().getModelSelectionForFeature(FeatureType.CODE_COMPLETION).modelId
        val stopTokens = buildList {
            if (infillRequest.stopTokens.isNotEmpty()) {
                addAll(infillRequest.stopTokens)
            }
        }.toMutableList()
        val prompt = if (settings.fimOverride) {
            settings.fimTemplate.stopTokens?.let { stopTokens.addAll(it) }
            settings.fimTemplate.buildPrompt(infillRequest)
        } else {
            infillRequest.prefix
        }

        val body = JsonMapper.mapper.writeValueAsString(
            mapOf(
                "model" to model,
                "prompt" to prompt,
                "suffix" to if (settings.fimOverride) null else infillRequest.suffix,
                "stream" to false,
                "raw" to true,
                "options" to mapOf(
                    "stop" to stopTokens.ifEmpty { null },
                    "num_predict" to MAX_COMPLETION_TOKENS,
                    "temperature" to 0.4
                ).filterValues { it != null }
            ).filterValues { it != null }
        )

        val headers = buildMap {
            apiKey?.takeIf { it.isNotBlank() }?.let {
                put(HttpHeaders.Authorization, "Bearer $it")
            }
        }

        return parseOllamaCompletion(
            postCompletionJson(
                client = baseClient,
                url = "$baseUrl/api/generate",
                headers = headers,
                body = body
            )
        )
    }

    override fun close() {
        delegate.close()
    }
}
