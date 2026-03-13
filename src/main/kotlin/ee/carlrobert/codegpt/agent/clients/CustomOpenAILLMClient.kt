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
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.buildStreamFrameFlow
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import ee.carlrobert.codegpt.codecompletions.InfillPromptTemplate
import ee.carlrobert.codegpt.codecompletions.InfillRequest
import ee.carlrobert.codegpt.agent.normalizeToolArgumentsJson
import ee.carlrobert.codegpt.settings.Placeholder
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceCodeCompletionSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceChatCompletionSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServicePlaceholders
import ee.carlrobert.codegpt.completions.factory.ResponsesApiUtil
import ee.carlrobert.codegpt.util.JsonMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.HttpHeaders
import org.apache.commons.text.StringEscapeUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.*
import java.net.URI
import java.util.UUID
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock

/**
 * Configuration settings for connecting to the CustomOpenAI API.
 *
 * @property baseUrl The base URL of the CustomOpenAI API. Default is "https://CustomOpenAI.ai/api/v1".
 * @property timeoutConfig Configuration for connection timeouts including request, connection, and socket timeouts.
 */
class CustomOpenAIClientSettings(
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
class CustomOpenAILLMClient(
    private val apiKey: String,
    private val settings: CustomOpenAIClientSettings,
    private val chatState: CustomServiceChatCompletionSettingsState? = null,
    private val codeCompletionState: CustomServiceCodeCompletionSettingsState? = null,
    private val baseClient: HttpClient = HttpClient(),
    clock: Clock = Clock.System
) : AbstractOpenAILLMClient<CustomOpenAIChatCompletionResponse, CustomOpenAIChatCompletionStreamResponse>(
    apiKey,
    settings,
    baseClient,
    clock,
    staticLogger,
    OpenAICompatibleToolDescriptorSchemaGenerator()
),
    CodeCompletionCapable {
    data object CustomOpenAI : LLMProvider("custom-openai", "Custom OpenAI")

    private val isResponsesApi: Boolean = ResponsesApiUtil.isResponsesApiUrl(chatState?.url)

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
            val settings = createClientSettings(
                url = state.url ?: throw IllegalStateException("Url not set"),
                timeoutConfig = timeoutConfig
            )
            val clientWithCustomHeaders = baseClient.config {
                defaultRequest {
                    state.headers.forEach { (key, value) ->
                        val normalizedKey = key.trim()
                        if (normalizedKey.isEmpty()
                            || normalizedKey.equals("Authorization", ignoreCase = true)
                        ) {
                            return@forEach
                        }

                        header(
                            normalizedKey,
                            value.replace(CUSTOM_SERVICE_API_KEY_PLACEHOLDER, apiKey)
                        )
                    }
                }
            }
            return CustomOpenAILLMClient(
                apiKey = apiKey,
                settings = settings,
                chatState = state,
                baseClient = clientWithCustomHeaders
            )
        }

        fun fromCodeCompletionSettingsState(
            apiKey: String,
            state: CustomServiceCodeCompletionSettingsState,
            baseClient: HttpClient = HttpClient(),
            timeoutConfig: ConnectionTimeoutConfig = ConnectionTimeoutConfig()
        ): CustomOpenAILLMClient {
            return CustomOpenAILLMClient(
                apiKey = apiKey,
                settings = createClientSettings(
                    url = state.url ?: throw IllegalStateException("Url not set"),
                    timeoutConfig = timeoutConfig
                ),
                codeCompletionState = state,
                baseClient = baseClient
            )
        }

        private fun createClientSettings(
            url: String,
            timeoutConfig: ConnectionTimeoutConfig
        ): CustomOpenAIClientSettings {
            val uri = URI.create(url)
            val authority = uri.authority ?: uri.host
            return CustomOpenAIClientSettings(
                baseUrl = "${uri.scheme}://${authority}",
                chatCompletionsPath = uri.path,
                timeoutConfig = timeoutConfig
            )
        }
    }

    override fun llmProvider(): LLMProvider = CustomOpenAI

    override suspend fun getCodeCompletion(infillRequest: InfillRequest): String {
        val state = requireNotNull(codeCompletionState) {
            "Custom OpenAI code completion requested on a chat-only client"
        }
        val url = requireNotNull(state.url)
        val payload = postCompletionJson(
            client = baseClient,
            url = url,
            headers = replaceCredentialPlaceholders(state.headers, apiKey),
            body = buildCodeCompletionRequestBody(state, infillRequest)
        )

        return if (
            state.infillTemplate == InfillPromptTemplate.CHAT_COMPLETION ||
            state.parseResponseAsChatCompletions
        ) {
            parseOpenAIChatCompletion(payload)
        } else {
            parseOpenAITextCompletion(payload)
        }
    }

    override fun serializeProviderChatRequest(
        messages: List<OpenAIMessage>,
        model: LLModel,
        tools: List<OpenAITool>?,
        toolChoice: OpenAIToolChoice?,
        params: LLMParams,
        stream: Boolean
    ): String {
        val state = requireNotNull(chatState) {
            "Custom OpenAI chat request requested on a code-completion-only client"
        }

        if (isResponsesApi) {
            return serializeResponsesApiRequest(state, messages, model, tools, toolChoice)
        }

        val customParams: CustomOpenAIParams = params.toCustomOpenAIParams(state)
        val streamRequest = state.shouldStream()
        val additionalProperties = buildCustomOpenAIAdditionalProperties(
            body = state.body,
            messages = messages,
            streamRequest = streamRequest,
            credential = apiKey,
            json = json
        )
        val responseFormat = createResponseFormat(customParams.schema, model)
        val request = CustomOpenAIChatCompletionRequest(
            messages = messages,
            model = model.id,
            stream = streamRequest,
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
            additionalProperties = additionalProperties,
        )

        return json.encodeToString(CustomOpenAIChatCompletionRequestSerializer, request)
    }

    /**
     * Builds the Responses API request body directly from the template body configuration.
     * The template body uses "input" instead of "messages" and "max_output_tokens" instead
     * of "max_tokens", so we process it as-is to produce the correct format.
     */
    private fun serializeResponsesApiRequest(
        state: CustomServiceChatCompletionSettingsState,
        messages: List<OpenAIMessage>,
        model: LLModel,
        tools: List<OpenAITool>?,
        toolChoice: OpenAIToolChoice?
    ): String {
        val streamRequest = state.shouldStream()
        val inputJson = messages.toResponsesApiItemsJson()
        val prompt = renderCustomOpenAIPrompt(messages, json)

        return buildJsonObject {
            state.body.forEach { (key, value) ->
                put(
                    key, transformCustomOpenAIBodyValue(
                        key = key,
                        value = value,
                        streamRequest = streamRequest,
                        messagesJson = inputJson,
                        prompt = prompt,
                        credential = apiKey,
                        json = json
                    )
                )
            }
            put("model", JsonPrimitive(model.id))
            if (!tools.isNullOrEmpty()) {
                put("tools", JsonArray(tools.map { it.toResponsesApiToolJson() }))
            }
            toolChoice?.toResponsesApiToolChoiceJson()?.let { put("tool_choice", it) }
        }.toString()
    }

    private fun buildCodeCompletionRequestBody(
        state: CustomServiceCodeCompletionSettingsState,
        infillRequest: InfillRequest
    ): String {
        return if (state.infillTemplate == InfillPromptTemplate.CHAT_COMPLETION) {
            val messages = listOf(
                mapOf(
                    "role" to "system",
                    "content" to (
                        "You are a code completion assistant. Complete the code between the given prefix and suffix. " +
                            "Return only the missing code that should be inserted, without any formatting, explanations, or markdown."
                        )
                ),
                mapOf(
                    "role" to "user",
                    "content" to (
                        "<PREFIX>\n${infillRequest.prefix}\n</PREFIX>\n\n" +
                            "<SUFFIX>\n${infillRequest.suffix}\n</SUFFIX>\n\nComplete:"
                        )
                )
            )
            val transformedBody = state.body.entries.mapNotNull { (key, value) ->
                when (key.lowercase()) {
                    "messages" -> key to messages
                    "prompt", "suffix" -> null
                    "stream" -> key to false
                    else -> key to transformCodeCompletionValue(
                        value = value,
                        template = InfillPromptTemplate.CHAT_COMPLETION,
                        infillRequest = infillRequest
                    )
                }
            }.toMap().toMutableMap()

            if (!transformedBody.containsKey("messages")) {
                transformedBody["messages"] = messages
            }

            JsonMapper.mapper.writeValueAsString(transformedBody)
        } else {
            JsonMapper.mapper.writeValueAsString(
                state.body.entries.associate { (key, value) ->
                    when (key.lowercase()) {
                        "stop" -> key to transformCodeCompletionStopValue(
                            value = value,
                            template = state.infillTemplate,
                            infillRequest = infillRequest
                        )

                        "stream" -> key to false
                        else -> key to transformCodeCompletionValue(
                            value = value,
                            template = state.infillTemplate,
                            infillRequest = infillRequest
                        )
                    }
                }
            )
        }
    }

    private fun replaceCredentialPlaceholders(
        headers: Map<String, String>,
        credential: String?
    ): Map<String, String> {
        return headers.mapValues { (_, value) ->
            if (credential != null && value.contains(CUSTOM_SERVICE_API_KEY_PLACEHOLDER)) {
                value.replace(CUSTOM_SERVICE_API_KEY_PLACEHOLDER, credential)
            } else {
                value
            }
        }
    }

    private fun transformCodeCompletionStopValue(
        value: Any,
        template: InfillPromptTemplate,
        infillRequest: InfillRequest
    ): Any {
        if (value !is String) {
            return transformCodeCompletionValue(value, template, infillRequest)
        }

        if (value.isEmpty()) {
            return value
        }

        return if (value.startsWith("[") && value.endsWith("]")) {
            ObjectMapper().readValue(value, object : TypeReference<List<String>>() {})
        } else {
            value.split(",").map { StringEscapeUtils.unescapeJava(it.trim()) }
        }
    }

    private fun transformCodeCompletionValue(
        value: Any,
        template: InfillPromptTemplate,
        infillRequest: InfillRequest
    ): Any {
        if (value !is String) {
            return when (value) {
                is Map<*, *> -> value.entries
                    .filter { it.key != null }
                    .associate { (nestedKey, nestedValue) ->
                        nestedKey.toString() to transformCodeCompletionValue(
                            value = nestedValue ?: "",
                            template = template,
                            infillRequest = infillRequest
                        )
                    }

                is Iterable<*> -> value.map {
                    transformCodeCompletionValue(it ?: "", template, infillRequest)
                }

                is Array<*> -> value.map {
                    transformCodeCompletionValue(it ?: "", template, infillRequest)
                }

                else -> value
            }
        }

        val replaced = if (value.contains(CUSTOM_SERVICE_API_KEY_PLACEHOLDER)) {
            value.replace(CUSTOM_SERVICE_API_KEY_PLACEHOLDER, apiKey)
        } else {
            value
        }

        return when (replaced) {
            Placeholder.FIM_PROMPT.code -> template.buildPrompt(infillRequest)
            Placeholder.PREFIX.code -> infillRequest.prefix
            Placeholder.SUFFIX.code -> infillRequest.suffix
            else -> replaced.takeIf {
                it.contains(Placeholder.PREFIX.code) || it.contains(Placeholder.SUFFIX.code)
            }?.replace(Placeholder.PREFIX.code, infillRequest.prefix)
                ?.replace(Placeholder.SUFFIX.code, infillRequest.suffix)
                ?: replaced
        }
    }

    override fun processProviderChatResponse(response: CustomOpenAIChatCompletionResponse): List<LLMChoice> {
        require(response.choices.isNotEmpty()) { "Empty choices in response" }
        return response.choices.map {
            it.message.toResponses(it.finishReason, createMetaInfo(response.usage))
        }
    }

    override fun decodeStreamingResponse(data: String): CustomOpenAIChatCompletionStreamResponse {
        val payload = normalizeSsePayload(data)
            ?: return CustomOpenAIChatCompletionStreamResponse(
                choices = emptyList(),
                created = 0,
                id = "",
                model = ""
            )
        if (!isResponsesApi) {
            return json.decodeFromString(payload)
        }
        return adaptResponsesApiStreamEvent(payload)
    }

    override fun decodeResponse(data: String): CustomOpenAIChatCompletionResponse {
        if (!isResponsesApi) {
            return json.decodeFromString(data)
        }
        return adaptResponsesApiResponse(data)
    }

    /**
     * Adapts a Responses API SSE event into a [CustomOpenAIChatCompletionStreamResponse].
     * Maps Responses API event types to the chat completions delta format that
     * [processStreamingResponse] already knows how to handle.
     */
    private fun adaptResponsesApiStreamEvent(data: String): CustomOpenAIChatCompletionStreamResponse {
        val event = json.parseToJsonElement(data).jsonObject
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: ""

        val choice = when (type) {
            "response.output_text.delta" -> {
                val delta = event["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                CustomOpenAIStreamChoice(
                    delta = CustomOpenAIStreamDelta(content = delta)
                )
            }

            "response.function_call_arguments.delta",
            "response.output_item.added" -> null

            "response.completed" -> {
                val responseObject = event["response"]?.jsonObject
                val toolCalls = responseObject?.get("output")
                    ?.jsonArray
                    ?.mapIndexedNotNull { index, item ->
                        val itemObject = item.jsonObject
                        if (itemObject["type"]?.jsonPrimitive?.contentOrNull != "function_call") {
                            return@mapIndexedNotNull null
                        }

                        val rawArguments = when (val arguments = itemObject["arguments"]) {
                            is JsonPrimitive -> arguments.contentOrNull
                            is JsonObject -> arguments.toString()
                            else -> null
                        }
                        CustomOpenAIToolCall(
                            id = itemObject["call_id"]?.jsonPrimitive?.contentOrNull,
                            index = index,
                            function = CustomOpenAIFunction(
                                name = itemObject["name"]?.jsonPrimitive?.contentOrNull,
                                arguments = normalizeToolArgumentsJson(rawArguments) ?: rawArguments.orEmpty()
                            )
                        )
                    }
                    .orEmpty()
                CustomOpenAIStreamChoice(
                    finishReason = "stop",
                    delta = CustomOpenAIStreamDelta(
                        toolCalls = toolCalls.takeIf { it.isNotEmpty() }
                    )
                )
            }

            else -> null
        }

        return CustomOpenAIChatCompletionStreamResponse(
            choices = listOfNotNull(choice),
            created = 0,
            id = "",
            model = ""
        )
    }

    /**
     * Adapts a non-streaming Responses API response into a [CustomOpenAIChatCompletionResponse].
     * Builds a synthetic chat-completions-format JSON and deserializes it, avoiding direct
     * construction of Koog internal types.
     */
    private fun adaptResponsesApiResponse(data: String): CustomOpenAIChatCompletionResponse {
        val response = json.parseToJsonElement(data).jsonObject
        val output = response["output"]?.jsonArray ?: JsonArray(emptyList())

        val textContent = StringBuilder()
        val toolCallsJson = mutableListOf<JsonElement>()

        for (item in output) {
            val itemObj = item.jsonObject
            when (itemObj["type"]?.jsonPrimitive?.contentOrNull) {
                "message" -> {
                    itemObj["content"]?.jsonArray?.forEach { contentPart ->
                        val partObj = contentPart.jsonObject
                        if (partObj["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                            textContent.append(partObj["text"]?.jsonPrimitive?.contentOrNull ?: "")
                        }
                    }
                }

                "function_call" -> {
                    val rawArguments = when (val arguments = itemObj["arguments"]) {
                        is JsonPrimitive -> arguments.contentOrNull
                        is JsonObject -> arguments.toString()
                        else -> null
                    }
                    toolCallsJson.add(buildJsonObject {
                        put("id", itemObj["call_id"] ?: JsonPrimitive(""))
                        put("type", JsonPrimitive("function"))
                        putJsonObject("function") {
                            put("name", itemObj["name"] ?: JsonPrimitive(""))
                            put(
                                "arguments",
                                JsonPrimitive(normalizeToolArgumentsJson(rawArguments) ?: rawArguments ?: "{}")
                            )
                        }
                    })
                }
            }
        }

        val syntheticJson = buildJsonObject {
            put("id", response["id"] ?: JsonPrimitive(""))
            put("created", JsonPrimitive(0))
            put("model", response["model"] ?: JsonPrimitive(""))
            put("object", JsonPrimitive("chat.completion"))
            putJsonArray("choices") {
                addJsonObject {
                    put("finish_reason", JsonPrimitive("stop"))
                    putJsonObject("message") {
                        put("role", JsonPrimitive("assistant"))
                        if (textContent.isNotEmpty()) {
                            put("content", JsonPrimitive(textContent.toString()))
                        }
                        if (toolCallsJson.isNotEmpty()) {
                            put("tool_calls", JsonArray(toolCallsJson))
                        }
                    }
                }
            }
            response["usage"]?.let { put("usage", parseResponsesApiUsageJson(it)) }
        }

        return json.decodeFromString(syntheticJson.toString())
    }

    /**
     * Converts Responses API usage JSON (input_tokens/output_tokens) to
     * OpenAI-compatible usage JSON (prompt_tokens/completion_tokens/total_tokens).
     */
    private fun parseResponsesApiUsageJson(usageElement: JsonElement): JsonElement {
        val usageObj = usageElement.jsonObject
        val inputTokens = usageObj["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val outputTokens = usageObj["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        return buildJsonObject {
            put("prompt_tokens", JsonPrimitive(inputTokens))
            put("completion_tokens", JsonPrimitive(outputTokens))
            put("total_tokens", JsonPrimitive(inputTokens + outputTokens))
        }
    }

    override fun processStreamingResponse(
        response: Flow<CustomOpenAIChatCompletionStreamResponse>
    ): Flow<StreamFrame> = buildStreamFrameFlow {
        var finishReason: String? = null
        var metaInfo: ResponseMetaInfo? = null

        response.collect { chunk ->
            chunk.choices.firstOrNull()?.let { choice ->
                choice.delta.content?.let { emitTextDelta(it) }

                choice.delta.toolCalls?.forEach { openAIToolCall ->
                    val index = openAIToolCall.index ?: 0
                    val id = openAIToolCall.id.orEmpty()
                    val functionName = openAIToolCall.function.name.orEmpty()
                    val functionArgs = openAIToolCall.function.arguments.orEmpty()
                    emitToolCallDelta(id, functionName, functionArgs, index)
                }

                choice.finishReason?.let { finishReason = it }
            }

            chunk.usage?.let { metaInfo = createMetaInfo(it) }
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
        val contentText = content?.text()

        if (this is OpenAIMessage.Assistant) {
            val assistantToolCalls = toolCalls
            if (!assistantToolCalls.isNullOrEmpty()) {
                return buildList {
                    contentText?.let {
                        add(
                            Message.Assistant(
                                content = it,
                                finishReason = finishReason,
                                metaInfo = metaInfo
                            )
                        )
                    }

                    assistantToolCalls.forEach { toolCall ->
                        val arguments = normalizeToolArgumentsJson(toolCall.function.arguments) ?: "{}"
                        add(
                            Message.Tool.Call(
                                id = toolCall.id,
                                tool = toolCall.function.name,
                                content = arguments,
                                metaInfo = metaInfo
                            )
                        )
                    }
                }
            }

            val reasoning = reasoningContent
            if (reasoning != null && contentText != null) {
                return listOf(
                    Message.Reasoning(
                        content = reasoning,
                        metaInfo = metaInfo
                    ),
                    Message.Assistant(
                        content = contentText,
                        finishReason = finishReason,
                        metaInfo = metaInfo
                    )
                )
            }
        }

        if (contentText != null) {
            return listOf(
                Message.Assistant(
                    content = contentText,
                    finishReason = finishReason,
                    metaInfo = metaInfo
                )
            )
        }

        val exception = LLMClientException(
            clientName,
            "Unexpected response: no tool calls and no content"
        )
        logger.error(exception) { exception.message }
        throw exception
    }
}

internal fun buildCustomOpenAIAdditionalProperties(
    body: Map<String, Any>,
    messages: List<OpenAIMessage>,
    streamRequest: Boolean,
    credential: String,
    json: Json
): Map<String, JsonElement>? = body
    .filterKeys { it !in CUSTOM_OPENAI_RESERVED_BODY_KEYS }
    .mapValues { (key, value) ->
        transformCustomOpenAIBodyValue(
            key = key,
            value = value,
            streamRequest = streamRequest,
            messages = messages,
            credential = credential,
            json = json
        )
    }
    .takeIf { it.isNotEmpty() }

internal fun transformCustomOpenAIBodyValue(
    key: String?,
    value: Any?,
    streamRequest: Boolean,
    messages: List<OpenAIMessage>,
    credential: String,
    json: Json
): JsonElement {
    return transformCustomOpenAIBodyValue(
        key = key,
        value = value,
        streamRequest = streamRequest,
        messagesJson = json.encodeToJsonElement(
            ListSerializer(OpenAIMessage.serializer()),
            messages
        ),
        prompt = renderCustomOpenAIPrompt(messages, json),
        credential = credential,
        json = json
    )
}

private fun transformCustomOpenAIBodyValue(
    key: String?,
    value: Any?,
    streamRequest: Boolean,
    messagesJson: JsonElement,
    prompt: String,
    credential: String,
    json: Json
): JsonElement {
    return when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> when {
            !streamRequest && key == "stream" -> JsonPrimitive(false)
            CustomServicePlaceholders.isMessages(value) -> messagesJson

            CustomServicePlaceholders.isPrompt(value) -> JsonPrimitive(prompt)

            value.contains($$"$CUSTOM_SERVICE_API_KEY") -> {
                JsonPrimitive(value.replace($$"$CUSTOM_SERVICE_API_KEY", credential))
            }

            else -> JsonPrimitive(value)
        }

        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(
            value.entries
                .filter { (nestedKey, _) -> nestedKey != null }
                .associate { (nestedKey, nestedValue) ->
                    nestedKey.toString() to transformCustomOpenAIBodyValue(
                        key = nestedKey.toString(),
                        value = nestedValue,
                        streamRequest = streamRequest,
                        messagesJson = messagesJson,
                        prompt = prompt,
                        credential = credential,
                        json = json
                    )
                }
        )

        is Iterable<*> -> JsonArray(
            value.map { item ->
                transformCustomOpenAIBodyValue(
                    key = null,
                    value = item,
                    streamRequest = streamRequest,
                    messagesJson = messagesJson,
                    prompt = prompt,
                    credential = credential,
                    json = json
                )
            }
        )

        is Array<*> -> JsonArray(
            value.map { item ->
                transformCustomOpenAIBodyValue(
                    key = null,
                    value = item,
                    streamRequest = streamRequest,
                    messagesJson = messagesJson,
                    prompt = prompt,
                    credential = credential,
                    json = json
                )
            }
        )

        else -> JsonPrimitive(value.toString())
    }
}

internal fun renderCustomOpenAIPrompt(messages: List<OpenAIMessage>, json: Json): String {
    return messages.joinToString(separator = "\n\n") { message ->
        message.content?.text()?.takeIf { it.isNotBlank() }
            ?: json.encodeToString(OpenAIMessage.serializer(), message)
    }
}

private fun List<OpenAIMessage>.toResponsesApiItemsJson(): JsonArray {
    return JsonArray(
        buildList {
            for (message in this@toResponsesApiItemsJson) {
                addAll(message.toResponsesApiItemsJson())
            }
        }
    )
}

private fun OpenAIMessage.toResponsesApiItemsJson(): List<JsonObject> {
    return when (this) {
        is OpenAIMessage.System, is OpenAIMessage.Developer -> listOf(
            buildResponsesApiInputMessageItemJson(
                role = "developer",
                content = content.toResponsesApiInputContentJson()
            )
        )

        is OpenAIMessage.User -> listOf(
            buildResponsesApiInputMessageItemJson(
                role = "user",
                content = content.toResponsesApiInputContentJson()
            )
        )

        is OpenAIMessage.Assistant -> buildList {
            reasoningContent
                ?.takeIf { it.isNotBlank() }
                ?.let { reasoning ->
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("reasoning"))
                            put("id", JsonPrimitive(UUID.randomUUID().toString()))
                            putJsonArray("summary") {
                                addJsonObject {
                                    put("type", JsonPrimitive("summary_text"))
                                    put("text", JsonPrimitive(reasoning))
                                }
                            }
                        }
                    )
                }

            content?.text()
                ?.takeIf { it.isNotBlank() }
                ?.let { assistantText ->
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("message"))
                            put("role", JsonPrimitive("assistant"))
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", JsonPrimitive("output_text"))
                                    put("text", JsonPrimitive(assistantText))
                                    put("annotations", JsonArray(emptyList()))
                                }
                            }
                        }
                    )
                }

            toolCalls.orEmpty().forEach { toolCall ->
                val arguments = normalizeToolArgumentsJson(toolCall.function.arguments) ?: "{}"
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("function_call"))
                        put("arguments", JsonPrimitive(arguments))
                        put("call_id", JsonPrimitive(toolCall.id))
                        put("name", JsonPrimitive(toolCall.function.name))
                    }
                )
            }
        }

        is OpenAIMessage.Tool -> listOf(
            buildJsonObject {
                put("type", JsonPrimitive("function_call_output"))
                put("call_id", JsonPrimitive(toolCallId))
                put("output", JsonPrimitive(content?.text().orEmpty()))
            }
        )
    }
}

private fun buildResponsesApiInputMessageItemJson(
    role: String,
    content: JsonArray
): JsonObject {
    return buildJsonObject {
        put("type", JsonPrimitive("message"))
        put("role", JsonPrimitive(role))
        put("content", content)
    }
}

private fun Content?.toResponsesApiInputContentJson(): JsonArray {
    if (this == null) {
        return JsonArray(emptyList())
    }

    return when (this) {
        is Content.Parts -> JsonArray(value.mapNotNull { it.toResponsesApiInputContentJson() })
        else -> JsonArray(
            listOf(
                buildJsonObject {
                    put("type", JsonPrimitive("input_text"))
                    put("text", JsonPrimitive(text()))
                }
            )
        )
    }
}

private fun OpenAIContentPart.toResponsesApiInputContentJson(): JsonObject? {
    return when (this) {
        is OpenAIContentPart.Text -> buildJsonObject {
            put("type", JsonPrimitive("input_text"))
            put("text", JsonPrimitive(text))
        }

        is OpenAIContentPart.Image -> buildJsonObject {
            put("type", JsonPrimitive("input_image"))
            imageUrl.detail?.let { put("detail", JsonPrimitive(it)) }
            put("imageUrl", JsonPrimitive(imageUrl.url))
        }

        is OpenAIContentPart.File -> buildJsonObject {
            put("type", JsonPrimitive("input_file"))
            file.fileData?.let { put("fileData", JsonPrimitive(it)) }
            file.fileId?.let { put("fileId", JsonPrimitive(it)) }
            file.filename?.let { put("filename", JsonPrimitive(it)) }
        }

        else -> null
    }
}

internal fun OpenAITool.toResponsesApiToolJson(): JsonObject {
    return buildJsonObject {
        put("type", JsonPrimitive("function"))
        put("name", JsonPrimitive(function.name))
        put(
            "parameters",
            function.parameters ?: buildJsonObject {
                put("type", JsonPrimitive("object"))
                putJsonObject("properties") {}
                putJsonArray("required") {}
            }
        )
        function.strict?.let { put("strict", JsonPrimitive(it)) }
        function.description?.takeIf { it.isNotBlank() }?.let {
            put("description", JsonPrimitive(it))
        }
    }
}

internal fun OpenAIToolChoice.toResponsesApiToolChoiceJson(): JsonElement {
    return when (this) {
        is OpenAIToolChoice.Function -> buildJsonObject {
            put("type", JsonPrimitive("function"))
            put("name", JsonPrimitive(function.name))
        }

        is OpenAIToolChoice.Mode -> JsonPrimitive(value)
    }
}

internal object CustomOpenAIChatCompletionRequestSerializer :
    CustomOpenAIAdditionalPropertiesFlatteningSerializer(CustomOpenAIChatCompletionRequest.serializer())

abstract class CustomOpenAIAdditionalPropertiesFlatteningSerializer(tSerializer: KSerializer<CustomOpenAIChatCompletionRequest>) :
    JsonTransformingSerializer<CustomOpenAIChatCompletionRequest>(tSerializer) {

    private val additionalPropertiesField = "additional_properties"

    @OptIn(ExperimentalSerializationApi::class)
    private val knownProperties = tSerializer.descriptor.elementNames

    override fun transformSerialize(element: JsonElement): JsonElement {
        val obj = element.jsonObject

        return buildJsonObject {
            obj.entries.asSequence()
                .filterNot { (key, _) -> key == additionalPropertiesField }
                .forEach { (key, value) -> put(key, value) }

            obj[additionalPropertiesField]?.jsonObject?.entries
                ?.filterNot { (key, _) -> obj.containsKey(key) }
                ?.forEach { (key, value) -> put(key, value) }
        }
    }

    override fun transformDeserialize(element: JsonElement): JsonElement {
        val obj = element.jsonObject
        val (known, additional) = obj.entries.partition { (key, _) -> key in knownProperties }

        return buildJsonObject {
            known.forEach { (key, value) -> put(key, value) }

            if (additional.isNotEmpty()) {
                put(
                    additionalPropertiesField,
                    buildJsonObject {
                        additional.forEach { (key, value) -> put(key, value) }
                    }
                )
            }
        }
    }
}
