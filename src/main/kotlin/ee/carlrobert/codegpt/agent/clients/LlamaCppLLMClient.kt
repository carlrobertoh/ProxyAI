package ee.carlrobert.codegpt.agent.clients

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMProvider
import ee.carlrobert.codegpt.codecompletions.InfillRequest
import ee.carlrobert.codegpt.completions.llama.LlamaModel
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettingsState
import ee.carlrobert.codegpt.util.JsonMapper
import io.ktor.client.*

class LlamaCppLLMClient(
    private val baseUrl: String,
    private val state: LlamaSettingsState,
    private val baseClient: HttpClient = HttpClient()
) : OpenAILLMClient(
    apiKey = "",
    settings = OpenAIClientSettings(
        baseUrl = baseUrl,
        chatCompletionsPath = "v1/chat/completions"
    ),
    baseClient = baseClient
) ,
    CodeCompletionCapable {
    data object LlamaCpp : LLMProvider("llama_cpp", "llama.cpp")

    override fun llmProvider(): LLMProvider = LlamaCpp

    override suspend fun getCodeCompletion(infillRequest: InfillRequest): String {
        val promptTemplate = if (state.isUseCustomModel) {
            state.localModelInfillPromptTemplate
        } else {
            LlamaModel.findByHuggingFaceModel(state.huggingFaceModel).infillPromptTemplate
        }
        val stopTokens = buildList {
            promptTemplate.stopTokens?.let { addAll(it) }
            if (infillRequest.stopTokens.isNotEmpty()) {
                addAll(infillRequest.stopTokens)
            }
        }.ifEmpty { null }

        val body = JsonMapper.mapper.writeValueAsString(
            mapOf(
                "prompt" to promptTemplate.buildPrompt(infillRequest),
                "stream" to false,
                "n_predict" to MAX_COMPLETION_TOKENS,
                "temperature" to 0.0,
                "top_k" to state.topK,
                "top_p" to state.topP,
                "min_p" to state.minP,
                "repeat_penalty" to state.repeatPenalty,
                "stop" to stopTokens
            ).filterValues { it != null }
        )

        return parseLlamaCompletion(
            postCompletionJson(
                client = baseClient,
                url = "$baseUrl/completion",
                headers = emptyMap(),
                body = body
            )
        )
    }
}
