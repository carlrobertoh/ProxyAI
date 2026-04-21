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
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.buildStreamFrameFlow
import ee.carlrobert.codegpt.agent.clients.ProxyAIClientSettings.Companion.DEFAULT_AUTO_APPLY_PATH
import ee.carlrobert.codegpt.agent.clients.ProxyAIClientSettings.Companion.DEFAULT_USER_DETAILS_PATH
import ee.carlrobert.codegpt.completions.autoapply.AutoApplyRequest
import ee.carlrobert.codegpt.completions.autoapply.AutoApplyResponse
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTApiException
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTUserDetails
import ee.carlrobert.codegpt.util.JsonMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock

/**
 * Configuration settings for connecting to the ProxyAI API.
 */
class ProxyAIClientSettings(
    baseUrl: String = DEFAULT_BASE_URL,
    chatCompletionsPath: String = DEFAULT_CHAT_COMPLETIONS_PATH,
    timeoutConfig: ConnectionTimeoutConfig = ConnectionTimeoutConfig()
) : OpenAIBaseSettings(baseUrl, chatCompletionsPath, timeoutConfig) {
    companion object {
        const val DEFAULT_BASE_URL: String = "https://codegpt-api.carlrobert.ee"
        const val DEFAULT_CHAT_COMPLETIONS_PATH: String = "/v2/chat/completions"
        const val DEFAULT_AUTO_APPLY_PATH: String = "/v1/code/apply"
        const val DEFAULT_USER_DETAILS_PATH: String = "/v1/users/details"
    }
}

/**
 * Implementation of [LLMClient] for ProxyAI API.
 */
class ProxyAILLMClient(
    private val apiKey: String,
    private val settings: ProxyAIClientSettings = ProxyAIClientSettings(),
    private val baseClient: HttpClient = HttpClientProvider.createHttpClient(),
    clock: Clock = Clock.System
) : AbstractOpenAILLMClient<ProxyAIChatCompletionResponse, ProxyAIChatCompletionStreamResponse>(
    apiKey = apiKey,
    settings = settings,
    baseClient = baseClient,
    clock = clock,
    logger = staticLogger,
    toolsConverter = OpenAICompatibleToolDescriptorSchemaGenerator()
) {
    data object ProxyAI : LLMProvider("proxyai", "ProxyAI")

    companion object {
        private val staticLogger = KotlinLogging.logger { }
    }

    override fun llmProvider(): LLMProvider = ProxyAI

    fun getApplyEditCompletion(request: AutoApplyRequest): AutoApplyResponse {
        val response = runBlocking {
            baseClient.post("${settings.baseUrl}$DEFAULT_AUTO_APPLY_PATH") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(JsonMapper.mapper.writeValueAsString(request))
            }
        }
        val body = runBlocking { response.bodyAsText() }
        if (!response.status.isSuccess()) {
            throw toApiException(response.status.value, body)
        }
        return JsonMapper.mapper.readValue(body, AutoApplyResponse::class.java)
    }

    fun getUserDetails(requestApiKey: String = apiKey): CodeGPTUserDetails {
        val response = runBlocking {
            baseClient.get("${settings.baseUrl}$DEFAULT_USER_DETAILS_PATH") {
                header(HttpHeaders.Authorization, "Bearer $requestApiKey")
            }
        }
        val body = runBlocking { response.bodyAsText() }
        if (!response.status.isSuccess()) {
            throw toApiException(response.status.value, body)
        }
        return JsonMapper.mapper.readValue(body, CodeGPTUserDetails::class.java)
    }

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

    override fun processStreamingResponse(
        response: Flow<ProxyAIChatCompletionStreamResponse>
    ): Flow<StreamFrame> = buildStreamFrameFlow {
        var finishReason: String? = null
        var metaInfo: ResponseMetaInfo? = null

        response.collect { chunk ->
            chunk.choices.firstOrNull()?.let { choice ->
                choice.delta?.content?.let { emitTextDelta(it) }

                choice.delta?.toolCalls?.forEach { openAIToolCall ->
                    val index = openAIToolCall.index ?: 0
                    val id = openAIToolCall.id.orEmpty()
                    val functionName = openAIToolCall.function.name.orEmpty()
                    val functionArgs = openAIToolCall.function.arguments.orEmpty()
                    emitToolCallDelta(id, functionName, functionArgs, index)
                }

                choice.finishReason?.let { finishReason = it }
            }

            chunk.usage?.let { metaInfo = createProxyMetaInfo(it) }
        }

        emitEnd(finishReason, metaInfo)
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
        throw UnsupportedOperationException("Moderation not supported.")
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
                result
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

    private fun toApiException(status: Int, body: String): CodeGPTApiException {
        return runCatching {
            JsonMapper.mapper.readValue(body, CodeGPTApiException::class.java).apply {
                this.status = status
                if (detail.isNullOrBlank()) {
                    detail = body
                }
            }
        }.getOrElse {
            CodeGPTApiException(
                status = status,
                detail = body.ifBlank { "Request failed with status $status" }
            )
        }
    }
}
