package ee.carlrobert.codegpt.agent.clients

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.*
import ai.koog.prompt.executor.clients.openai.models.OpenAIChatCompletionResponse
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import ee.carlrobert.codegpt.codecompletions.InfillPromptTemplate
import ee.carlrobert.codegpt.codecompletions.InfillRequest
import ee.carlrobert.codegpt.settings.Placeholder
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceChatCompletionSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceCodeCompletionSettingsState
import ee.carlrobert.codegpt.settings.service.custom.CustomServicePlaceholders
import ee.carlrobert.codegpt.util.JsonMapper
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.api.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.*
import org.apache.commons.text.StringEscapeUtils
import java.net.URI
import kotlin.time.Clock

/**
 * Implementation of [LLMClient] for OpenAI-compatible custom providers.
 *
 * Chat-completions requests keep the custom body/placeholder behavior.
 * Responses requests follow Koog's [OpenAILLMClient] path and only add custom params.
 */
class CustomOpenAILLMClient(
    private val apiKey: String,
    settings: OpenAIClientSettings,
    private val chatState: CustomServiceChatCompletionSettingsState? = null,
    private val codeCompletionState: CustomServiceCodeCompletionSettingsState? = null,
    private val baseClient: HttpClient = HttpClient(),
    clock: Clock = Clock.System
) : OpenAILLMClient(
    apiKey = apiKey,
    settings = settings,
    baseClient = baseClient,
    clock = clock,
), CodeCompletionCapable {

    data object CustomOpenAI : LLMProvider("custom-openai", "Custom OpenAI")

    companion object {
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
                install(FlattenCustomOpenAIAdditionalPropertiesPlugin)
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
        ): OpenAIClientSettings {
            val uri = URI.create(url)
            val authority = uri.authority ?: uri.host
            val path = buildString {
                append(uri.path)
                uri.query?.takeIf { it.isNotBlank() }?.let { append("?").append(it) }
            }
            return OpenAIClientSettings(
                baseUrl = "${uri.scheme}://${authority}",
                chatCompletionsPath = path,
                responsesAPIPath = path,
                timeoutConfig = timeoutConfig
            )
        }
    }

    override fun llmProvider(): LLMProvider = CustomOpenAI

    private fun LLModel.hasResponsesEndpointCapability(): Boolean =
        this.capabilities?.any { it == LLMCapability.OpenAIEndpoint.Responses } == true

    private fun requireChatState(): CustomServiceChatCompletionSettingsState {
        return requireNotNull(chatState) {
            "Custom OpenAI chat request requested on a code-completion-only client"
        }
    }

    private fun requireCodeCompletionState(): CustomServiceCodeCompletionSettingsState {
        return requireNotNull(codeCompletionState) {
            "Custom OpenAI code completion requested on a chat-only client"
        }
    }

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): List<Message.Response> {
        val state = requireChatState()
        val finalPrompt = prompt.prepareForModel(model, state)
        return super.execute(finalPrompt, model, tools)
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> {
        val state = requireChatState()
        val finalPrompt = prompt.prepareForModel(model, state)
        return super.executeStreaming(finalPrompt, model, tools)
    }

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): List<LLMChoice> {
        val state = requireChatState()
        return super.executeMultipleChoices(prompt.prepareForModel(model, state), model, tools)
    }

    private fun Prompt.prepareForModel(
        model: LLModel,
        state: CustomServiceChatCompletionSettingsState
    ): Prompt {
        return when {
            model.hasResponsesEndpointCapability() ->
                withParams(params.toCustomOpenAIResponsesParams(state))

            params is OpenAIResponsesParams ->
                withParams(params.toCustomOpenAIParams(state))

            else -> this
        }
    }

    override fun decodeResponse(data: String): OpenAIChatCompletionResponse {
        return json.decodeFromString(data)
    }

    override suspend fun getCodeCompletion(infillRequest: InfillRequest): String {
        val state = requireCodeCompletionState()
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
        val state = requireChatState()
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
    return when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> when {
            !streamRequest && key == "stream" -> JsonPrimitive(false)
            CustomServicePlaceholders.isMessages(value) -> json.parseToJsonElement(
                json.encodeToString(ListSerializer(OpenAIMessage.serializer()), messages)
            )

            CustomServicePlaceholders.isPrompt(value) -> JsonPrimitive(
                renderCustomOpenAIPrompt(
                    messages,
                    json
                )
            )

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
                        messages = messages,
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
                    messages = messages,
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
                    messages = messages,
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

private val customOpenAIRequestTransformJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

private val FlattenCustomOpenAIAdditionalPropertiesPlugin = createClientPlugin(
    "FlattenCustomOpenAIAdditionalProperties"
) {
    transformRequestBody { _, content, _ ->
        val requestBody = content as? String ?: return@transformRequestBody null
        val flattened = flattenSerializedAdditionalProperties(
            requestBody,
            customOpenAIRequestTransformJson
        )
        if (flattened == requestBody) {
            null
        } else {
            TextContent(flattened, ContentType.Application.Json)
        }
    }
}

internal fun flattenSerializedAdditionalProperties(
    requestBody: String,
    json: Json
): String {
    val payload = runCatching { json.parseToJsonElement(requestBody) }.getOrNull()?.jsonObject
        ?: return requestBody
    val additionalProperties = payload["additional_properties"] as? JsonObject
        ?: return requestBody

    val flattened = buildJsonObject {
        payload.entries
            .filterNot { (key, _) -> key == "additional_properties" }
            .forEach { (key, value) -> put(key, value) }
        additionalProperties.entries
            .filterNot { (key, _) -> payload.containsKey(key) }
            .forEach { (key, value) -> put(key, value) }
    }

    return flattened.toString()
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
