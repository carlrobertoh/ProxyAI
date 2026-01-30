package ee.carlrobert.codegpt.agent.clients

import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.ktor.client.*

class InceptionAILLMClient(
    apiKey: String,
    baseClient: HttpClient = HttpClient()
) : OpenAILLMClient(
    apiKey = apiKey,
    settings = OpenAIClientSettings(
        baseUrl = "https://api.inceptionlabs.ai",
        chatCompletionsPath = "v1/chat/completions"
    ),
    baseClient = baseClient
) {
    data object Inception : LLMProvider("inception", "Inception")

    override fun llmProvider(): LLMProvider = Inception

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel
    ): ModerationResult {
        throw UnsupportedOperationException("Moderation is not supported by Inception API.")
    }
}
