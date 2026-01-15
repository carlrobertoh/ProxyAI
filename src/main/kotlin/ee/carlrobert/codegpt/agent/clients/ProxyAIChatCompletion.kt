package ee.carlrobert.codegpt.agent.clients

import ai.koog.prompt.executor.clients.openai.base.models.*
import ai.koog.prompt.executor.clients.serialization.AdditionalPropertiesFlatteningSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal class ProxyAIChatCompletionRequest(
    val messages: List<OpenAIMessage> = emptyList(),
    val prompt: String? = null,
    override val model: String? = null,
    override val stream: Boolean? = null,
    override val temperature: Double? = null,
    val tools: List<OpenAITool>? = null,
    val toolChoice: OpenAIToolChoice? = null,
    override val topP: Double? = null,
    override val topLogprobs: Int? = null,
    val maxTokens: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val responseFormat: OpenAIResponseFormat? = null,
    val stop: List<String>? = null,
    val logprobs: Boolean? = null,
    val seed: Int? = null,
    val logitBias: Map<Int, Double>? = null,
    val user: String? = null,
    val additionalProperties: Map<String, JsonElement>? = null,
) : OpenAIBaseLLMRequest

/**
 * Chat completion choice.
 */
@Serializable
public class ProxyAIChoice(
    public val finishReason: String? = null,
    public val nativeFinishReason: String? = null,
    public val message: OpenAIMessage,
    public val error: ProxyAIErrorResponse? = null
)

/**
 * Chat completion streaming choice.
 */
@Serializable
public class ProxyAIStreamChoice(
    public val finishReason: String? = null,
    public val nativeFinishReason: String? = null,
    public val delta: ProxyAIStreamDelta?,
    public val error: ProxyAIErrorResponse? = null
)

/**
 * @property content The contents of the chunk message.
 * @property role The role of the author of this message.
 * @property toolCalls The tool calls requested by the model.
 */
@Serializable
public class ProxyAIStreamDelta(
    public val content: String? = null,
    public val role: String? = null,
    public val toolCalls: List<ProxyAIToolCall>? = null
)

@Serializable
public class ProxyAIToolCall(
    public val id: String? = "",
    public val function: ProxyAIFunction
) {
    /** The type of the tool. Currently, only `function` is supported. */
    public val type: String = "function"
}

@Serializable
public class ProxyAIFunction(
    public val name: String? = "",
    public val arguments: String? = ""
)

/**
 * Represents an error response structure typically used for conveying error details to the clients.
 */
@Serializable
public class ProxyAIErrorResponse(
    public val code: Int,
    public val message: String,
    public val metadata: Map<String, String>? = null,
)

@Serializable
public class ProxyAICredits(
    public val prompt: Long? = null,
    public val completion: Long? = null,
    public val total: Long? = null,
    public val remaining: Long? = null,
)

@Serializable
public class ProxyAIUsage(
    @SerialName("prompt_tokens")
    public val promptTokens: Int? = null,
    @SerialName("completion_tokens")
    public val completionTokens: Int? = null,
    @SerialName("total_tokens")
    public val totalTokens: Int? = null,
    public val credits: ProxyAICredits? = null,
)

/**
 * ProxyAI Chat Completion Response.
 */
@Serializable
public class ProxyAIChatCompletionResponse(
    public val choices: List<ProxyAIChoice> = emptyList(),
    override val created: Long = 0L,
    override val id: String = "",
    override val model: String = "",
    public val systemFingerprint: String? = null,
    @SerialName("object")
    public val objectType: String = "chat.completion",
    public val usage: ProxyAIUsage? = null,
) : OpenAIBaseLLMResponse

/**
 * ProxyAI Chat Completion Streaming Response.
 */
@Serializable
public class ProxyAIChatCompletionStreamResponse(
    public val choices: List<ProxyAIStreamChoice> = emptyList(),
    override val created: Long = 0L,
    override val id: String = "",
    override val model: String = "",
    public val systemFingerprint: String? = null,
    @SerialName("object")
    public val objectType: String = "chat.completion.chunk",
    public val usage: ProxyAIUsage? = null,
) : OpenAIBaseLLMStreamResponse

internal object ProxyAIChatCompletionRequestSerializer :
    AdditionalPropertiesFlatteningSerializer<ProxyAIChatCompletionRequest>(
        ProxyAIChatCompletionRequest.serializer()
    )
