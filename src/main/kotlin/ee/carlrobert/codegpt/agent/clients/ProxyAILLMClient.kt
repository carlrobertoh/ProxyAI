package ee.carlrobert.codegpt.agent.clients

import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.openai.base.AbstractOpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.OpenAIBaseSettings
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIMessage
import ai.koog.prompt.executor.clients.openai.base.models.OpenAITool
import ai.koog.prompt.executor.clients.openai.base.models.OpenAIToolChoice
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrameFlowBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Configuration settings for connecting to the ProxyAI API.
 */
public class ProxyAIClientSettings(
    baseUrl: String = DEFAULT_BASE_URL,
    chatCompletionsPath: String = DEFAULT_CHAT_COMPLETIONS_PATH,
    timeoutConfig: ConnectionTimeoutConfig = ConnectionTimeoutConfig()
) : OpenAIBaseSettings(baseUrl, chatCompletionsPath, timeoutConfig) {
    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://codegpt-api.carlrobert.ee"
        public const val DEFAULT_CHAT_COMPLETIONS_PATH: String = "/v2/chat/completions"
    }
}

/**
 * Implementation of [LLMClient] for ProxyAI API.
 */
public class ProxyAILLMClient(
    apiKey: String,
    private val settings: ProxyAIClientSettings = ProxyAIClientSettings(),
    baseClient: HttpClient = HttpClient(),
    clock: Clock = Clock.System
) : AbstractOpenAILLMClient<ProxyAIChatCompletionResponse, ProxyAIChatCompletionStreamResponse>(
    apiKey,
    settings,
    baseClient,
    clock,
    staticLogger,
    OpenAICompatibleToolDescriptorSchemaGenerator()
) {
    public data object ProxyAI : LLMProvider("proxyai", "ProxyAI")

    companion object {
        private val staticLogger = KotlinLogging.logger { }

        init {
            registerOpenAIJsonSchemaGenerators(ProxyAI)
        }
    }

    override fun llmProvider(): LLMProvider = ProxyAI

    override fun serializeProviderChatRequest(
        messages: List<OpenAIMessage>,
        model: LLModel,
        tools: List<OpenAITool>?,
        toolChoice: OpenAIToolChoice?,
        params: LLMParams,
        stream: Boolean
    ): String {
        val proxyParams = params.toProxyAIParams()
        val responseFormat = createResponseFormat(params.schema, model)
        val request = ProxyAIChatCompletionRequest(
            messages = messages,
            model = model.id,
            stream = stream,
            temperature = proxyParams.temperature,
            tools = tools,
            toolChoice = proxyParams.toolChoice?.toOpenAIToolChoice(),
            topP = proxyParams.topP,
            topLogprobs = proxyParams.topLogprobs,
            maxTokens = proxyParams.maxTokens,
            frequencyPenalty = proxyParams.frequencyPenalty,
            presencePenalty = proxyParams.presencePenalty,
            responseFormat = responseFormat,
            stop = proxyParams.stop,
            logprobs = proxyParams.logprobs,
            user = proxyParams.user,
            additionalProperties = proxyParams.additionalProperties,
        )

        return json.encodeToString(ProxyAIChatCompletionRequestSerializer, request)
    }

    override fun processProviderChatResponse(response: ProxyAIChatCompletionResponse): List<LLMChoice> {
        if (response.choices.isEmpty()) {
            val errorMsg = "Empty choices in response. Response may contain an error."
            logger.error { errorMsg }
            throw LLMClientException(clientName, errorMsg)
        }

        // Check for errors in choices
        response.choices.forEach { choice ->
            choice.error?.let { error ->
                val errorMsg = "ProxyAI API error (code: ${error.code}): ${error.message}"
                logger.error { errorMsg }
                throw LLMClientException(clientName, errorMsg)
            }
        }

        return response.choices.map {
            it.message.toResponses(it.finishReason, createProxyMetaInfo(response.usage))
        }
    }

    override fun decodeStreamingResponse(data: String): ProxyAIChatCompletionStreamResponse =
        json.decodeFromString(data)

    override fun decodeResponse(data: String): ProxyAIChatCompletionResponse =
        json.decodeFromString(data)

    override suspend fun StreamFrameFlowBuilder.processStreamingChunk(chunk: ProxyAIChatCompletionStreamResponse) {
        chunk.choices.firstOrNull()?.let { choice ->
            choice.delta?.content?.let { emitAppend(it) }
            choice.delta?.toolCalls?.forEachIndexed { index, toolCall ->
                val id = toolCall.id
                val name = toolCall.function.name
                val arguments = toolCall.function.arguments
                upsertToolCall(index, id, name, arguments)
            }
            choice.finishReason?.let { emitEnd(it, createProxyMetaInfo(chunk.usage)) }
        }
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
        logger.warn { "Moderation is not supported by ProxyAI API" }
        throw UnsupportedOperationException("Moderation is not supported by ProxyAI API.")
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun OpenAIMessage.toResponses(
        finishReason: String?,
        metaInfo: ResponseMetaInfo
    ): List<Message.Response> {
        return when {
            this is OpenAIMessage.Assistant && !this.toolCalls.isNullOrEmpty() -> {
                val result = mutableListOf<Message.Response>()
                if (this.content != null) {
                    result.add(
                        Message.Assistant(
                            content = this.content!!.text(),
                            finishReason = finishReason,
                            metaInfo = metaInfo
                        )
                    )
                }

                this.toolCalls!!.forEach { toolCall ->
                    result.add(
                        Message.Tool.Call(
                            id = toolCall.id,
                            tool = toolCall.function.name,
                            content = toolCall.function.arguments
                                .takeIf { it.isNotEmpty() }
                                ?: "{}",
                            metaInfo = metaInfo
                        )
                    )
                }
                return result
            }

            this is OpenAIMessage.Assistant && this.reasoningContent != null && this.content != null -> listOf(
                Message.Reasoning(
                    content = this.reasoningContent!!,
                    metaInfo = metaInfo
                ),
                Message.Assistant(
                    content = this.content!!.text(),
                    finishReason = finishReason,
                    metaInfo = metaInfo
                )
            )

            this.content != null -> listOf(
                Message.Assistant(
                    content = this.content!!.text(),
                    finishReason = finishReason,
                    metaInfo = metaInfo
                )
            )

            else -> {
                val exception = LLMClientException(
                    clientName,
                    "Unexpected response: no tool calls and no content"
                )
                logger.error(exception) { exception.message }
                throw exception
            }
        }
    }

    private fun createProxyMetaInfo(usage: ProxyAIUsage?): ResponseMetaInfo {
        val metadata = usage?.credits?.toMetadata()
        return ResponseMetaInfo.create(
            clock = clock,
            totalTokensCount = usage?.totalTokens,
            inputTokensCount = usage?.promptTokens,
            outputTokensCount = usage?.completionTokens,
            metadata = metadata
        )
    }

    private fun ProxyAICredits.toMetadata(): JsonObject? {
        val creditsObject = buildJsonObject {
            putIfNotNull("prompt", prompt)
            putIfNotNull("completion", completion)
            putIfNotNull("total", total)
            putIfNotNull("remaining", remaining)
        }
        if (creditsObject.isEmpty()) return null
        return buildJsonObject {
            put("credits", creditsObject)
        }
    }

    private fun JsonObjectBuilder.putIfNotNull(key: String, value: Long?) {
        if (value != null) {
            put(key, JsonPrimitive(value))
        }
    }
}
