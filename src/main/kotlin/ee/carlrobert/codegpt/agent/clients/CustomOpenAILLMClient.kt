package ee.carlrobert.codegpt.agent.clients

import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.executor.clients.openai.base.AbstractOpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.OpenAIBaseSettings
import ai.koog.prompt.executor.clients.openai.base.OpenAICompatibleToolDescriptorSchemaGenerator
import ai.koog.prompt.executor.clients.openai.base.models.*
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrameFlowBuilder
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceChatCompletionSettingsState
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import kotlinx.datetime.Clock
import java.net.URI
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Configuration settings for connecting to the CustomOpenAI API.
 *
 * @property baseUrl The base URL of the CustomOpenAI API. Default is "https://CustomOpenAI.ai/api/v1".
 * @property timeoutConfig Configuration for connection timeouts including request, connection, and socket timeouts.
 */
public class CustomOpenAIClientSettings(
    baseUrl: String,
    chatCompletionsPath: String,
    timeoutConfig: ConnectionTimeoutConfig
) : OpenAIBaseSettings(baseUrl, chatCompletionsPath, timeoutConfig)

/**
 * Implementation of [LLMClient] for CustomOpenAI API.
 * CustomOpenAI is an API that routes requests to multiple LLM providers.
 *
 * @param apiKey The API key for the CustomOpenAI API
 * @param settings The base URL and timeouts for the CustomOpenAI API, defaults to "https://CustomOpenAI.ai" and 900s
 * @param clock Clock instance used for tracking response metadata timestamps.
 */
public class CustomOpenAILLMClient(
    apiKey: String,
    private val settings: CustomOpenAIClientSettings,
    private val state: CustomServiceChatCompletionSettingsState,
    baseClient: HttpClient = HttpClient(),
    clock: Clock = Clock.System
) : AbstractOpenAILLMClient<CustomOpenAIChatCompletionResponse, CustomOpenAIChatCompletionStreamResponse>(
    apiKey,
    settings,
    baseClient,
    clock,
    staticLogger,
    OpenAICompatibleToolDescriptorSchemaGenerator()
) {
    public data object CustomOpenAI : LLMProvider("custom-openai", "Custom OpenAI")


    companion object {
        private val staticLogger = KotlinLogging.logger { }

        init {
            registerOpenAIJsonSchemaGenerators(CustomOpenAI)
        }

        fun fromSettingsState(
            apiKey: String,
            state: CustomServiceChatCompletionSettingsState,
            baseClient: HttpClient = HttpClient(),
            timeoutConfig: ConnectionTimeoutConfig = ConnectionTimeoutConfig()
        ): CustomOpenAILLMClient {
            val stateUrl = state.url ?: throw IllegalStateException("Url not set")
            val uri = URI.create(stateUrl)
            val authority = uri.authority ?: uri.host
            val settings = CustomOpenAIClientSettings(
                baseUrl = "${uri.scheme}://${authority}",
                chatCompletionsPath = uri.path,
                timeoutConfig = timeoutConfig
            )
            return CustomOpenAILLMClient(apiKey, settings, state, baseClient)
        }
    }

    override fun llmProvider(): LLMProvider = CustomOpenAI

    override fun serializeProviderChatRequest(
        messages: List<OpenAIMessage>,
        model: LLModel,
        tools: List<OpenAITool>?,
        toolChoice: OpenAIToolChoice?,
        params: LLMParams,
        stream: Boolean
    ): String {
        val customParams = params.toCustomOpenAIParams(state)
        val responseFormat = createResponseFormat(params.schema, model)

        val request = CustomOpenAIChatCompletionRequest(
            messages = messages,
            model = model.id,
            stream = stream,
            temperature = customParams.temperature,
            tools = tools,
            toolChoice = customParams.toolChoice?.toOpenAIToolChoice(),
            topP = customParams.topP,
            topLogprobs = customParams.topLogprobs,
            maxTokens = customParams.maxTokens,
            frequencyPenalty = customParams.frequencyPenalty,
            presencePenalty = customParams.presencePenalty,
            responseFormat = responseFormat,
            stop = customParams.stop,
            logprobs = customParams.logprobs,
            topK = customParams.topK,
            repetitionPenalty = customParams.repetitionPenalty,
            minP = customParams.minP,
            topA = customParams.topA,
            prediction = customParams.speculation?.let { OpenAIStaticContent(Content.Text(it)) },
            transforms = customParams.transforms,
            models = customParams.models,
            route = customParams.route,
            user = customParams.user,
            additionalProperties = customParams.additionalProperties,
        )

        return json.encodeToString(CustomOpenAIChatCompletionRequestSerializer, request)
    }

    override fun processProviderChatResponse(response: CustomOpenAIChatCompletionResponse): List<LLMChoice> {
        require(response.choices.isNotEmpty()) { "Empty choices in response" }
        return response.choices.map {
            it.message.toResponses(it.finishReason, createMetaInfo(response.usage))
        }
    }

    override fun decodeStreamingResponse(data: String): CustomOpenAIChatCompletionStreamResponse =
        json.decodeFromString(data)

    override fun decodeResponse(data: String): CustomOpenAIChatCompletionResponse =
        json.decodeFromString(data)

    override suspend fun StreamFrameFlowBuilder.processStreamingChunk(chunk: CustomOpenAIChatCompletionStreamResponse) {
        chunk.choices.firstOrNull()?.let { choice ->
            choice.delta.content?.let { emitAppend(it) }
            choice.delta.toolCalls?.forEachIndexed { index, openAIToolCall ->
                val id = openAIToolCall.id
                val name = openAIToolCall.function.name
                val arguments = openAIToolCall.function.arguments
                upsertToolCall(index, id, name, arguments)
            }
            choice.finishReason?.let { emitEnd(it, createMetaInfo(chunk.usage)) }
        }
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
        logger.warn { "Moderation is not supported by CustomOpenAI API" }
        throw UnsupportedOperationException("Moderation is not supported by CustomOpenAI API.")
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
                            content = toolCall.function.arguments.takeIf { it.isNotEmpty() }
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
}
