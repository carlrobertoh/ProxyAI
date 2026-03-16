package ee.carlrobert.codegpt.agent.external

import ee.carlrobert.codegpt.toolwindow.agent.AcpConfigOption
import ee.carlrobert.codegpt.toolwindow.agent.AcpConfigOptionChoice
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal enum class AcpSessionConfigId(
    val value: String,
    val displayName: String
) {
    MODEL("model", "Model"),
    MODE("mode", "Mode");

    fun matches(option: AcpConfigOption): Boolean {
        return option.id == value || option.category == value
    }
}

internal data class AcpConfigUpdateRequest(
    val sessionId: String,
    val optionId: String,
    val value: String
) {
    fun toJsonObject(): JsonObject {
        return buildJsonObject {
            put("sessionId", sessionId)
            put("configId", optionId)
            put("value", value)
        }
    }
}

internal enum class AcpConfigUpdateMethod(val wireName: String) {
    SNAKE_CASE("session/set_config_option"),
    CAMEL_CASE("session/setConfigOption")
}

internal sealed interface AcpConfigUpdateSupport {
    fun candidateMethods(): List<AcpConfigUpdateMethod>

    data object Unknown : AcpConfigUpdateSupport {
        override fun candidateMethods(): List<AcpConfigUpdateMethod> =
            AcpConfigUpdateMethod.entries
    }

    data object Unsupported : AcpConfigUpdateSupport {
        override fun candidateMethods(): List<AcpConfigUpdateMethod> = emptyList()
    }

    data class Supported(val method: AcpConfigUpdateMethod) : AcpConfigUpdateSupport {
        override fun candidateMethods(): List<AcpConfigUpdateMethod> = listOf(method)
    }
}

internal sealed interface AcpConfigUpdateResult {
    data class Applied(
        val response: JsonObject,
        val support: AcpConfigUpdateSupport.Supported
    ) : AcpConfigUpdateResult

    data object Unsupported : AcpConfigUpdateResult
}

internal class AcpSessionConfigAdapter(
    private val json: Json
) {

    fun merge(
        existing: List<AcpConfigOption>,
        response: JsonObject
    ): List<AcpConfigOption> {
        val updates = decode(response)
        if (updates.isEmpty()) {
            return existing
        }

        val merged = existing.associateByTo(linkedMapOf()) { it.id }
        updates.forEach { option ->
            merged[option.id] = option
        }
        return merged.values.toList()
    }

    suspend fun updateOption(
        request: AcpConfigUpdateRequest,
        support: AcpConfigUpdateSupport,
        sendRequest: suspend (String, JsonObject) -> JsonObject
    ): AcpConfigUpdateResult {
        val candidateMethods = support.candidateMethods()
        if (candidateMethods.isEmpty()) {
            return AcpConfigUpdateResult.Unsupported
        }

        val params = request.toJsonObject()
        candidateMethods.forEach { method ->
            try {
                return AcpConfigUpdateResult.Applied(
                    response = sendRequest(method.wireName, params),
                    support = AcpConfigUpdateSupport.Supported(method)
                )
            } catch (error: Throwable) {
                if (!error.isMethodNotFoundJsonRpcError()) {
                    throw error
                }
            }
        }

        return AcpConfigUpdateResult.Unsupported
    }

    private fun decode(response: JsonObject): List<AcpConfigOption> {
        val directOptions = buildList {
            addAll(
                response.decodeField<List<AcpStandardConfigOptionPayload>>("configOptions")
                    .orEmpty()
                    .mapNotNull(AcpStandardConfigOptionPayload::toConfigOption)
            )
            response.decodeField<AcpStandardConfigOptionPayload>("configOption")
                ?.toConfigOption()
                ?.let(::add)
        }

        val hasDirectModelOption = directOptions.any(AcpSessionConfigId.MODEL::matches)
        val hasDirectModeOption = directOptions.any(AcpSessionConfigId.MODE::matches)

        return buildList {
            addAll(directOptions)
            if (!hasDirectModelOption) {
                response.decodeField<AcpModelsPayload>("models")
                    ?.toConfigOption()
                    ?.let(::add)
            }
            if (!hasDirectModeOption) {
                response.decodeField<AcpModesPayload>("modes")
                    ?.toConfigOption()
                    ?.let(::add)
            }
        }
    }

    private inline fun <reified T> JsonObject.decodeField(key: String): T? {
        val element = this[key] ?: return null
        return runCatching { json.decodeFromJsonElement<T>(element) }.getOrNull()
    }
}

@Serializable
private data class AcpStandardConfigOptionPayload(
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    val type: String? = null,
    val currentValue: String? = null,
    val current_value: String? = null,
    val value: String? = null,
    val options: List<AcpConfigChoicePayload> = emptyList()
) {
    fun toConfigOption(): AcpConfigOption? {
        val resolvedId = id.nullIfBlank() ?: return null
        return AcpConfigOption(
            id = resolvedId,
            name = name.nullIfBlank() ?: resolvedId,
            description = description.nullIfBlank(),
            category = category.nullIfBlank(),
            type = type.nullIfBlank(),
            currentValue = firstNotBlank(currentValue, current_value, value),
            options = options.mapNotNull(AcpConfigChoicePayload::toChoice)
        )
    }
}

@Serializable
private data class AcpConfigChoicePayload(
    val value: String? = null,
    val id: String? = null,
    val name: String? = null,
    val description: String? = null
) {
    fun toChoice(): AcpConfigOptionChoice? {
        val resolvedValue = firstNotBlank(value, id) ?: return null
        return AcpConfigOptionChoice(
            value = resolvedValue,
            name = name.nullIfBlank() ?: resolvedValue,
            description = description.nullIfBlank()
        )
    }
}

@Serializable
private data class AcpModelsPayload(
    val currentModelId: String? = null,
    val availableModels: List<AcpAlternativeChoicePayload> = emptyList()
) {
    fun toConfigOption(): AcpConfigOption? {
        return toAlternativeConfigOption(
            id = AcpSessionConfigId.MODEL,
            currentValue = currentModelId,
            entries = availableModels
        )
    }
}

@Serializable
private data class AcpModesPayload(
    val currentModeId: String? = null,
    val availableModes: List<AcpAlternativeChoicePayload> = emptyList()
) {
    fun toConfigOption(): AcpConfigOption? {
        return toAlternativeConfigOption(
            id = AcpSessionConfigId.MODE,
            currentValue = currentModeId,
            entries = availableModes
        )
    }
}

@Serializable
private data class AcpAlternativeChoicePayload(
    val modelId: String? = null,
    val modeId: String? = null,
    val value: String? = null,
    val id: String? = null,
    val name: String? = null,
    val description: String? = null
) {
    fun toChoice(): AcpConfigOptionChoice? {
        val resolvedValue = firstNotBlank(modelId, modeId, value, id) ?: return null
        return AcpConfigOptionChoice(
            value = resolvedValue,
            name = name.nullIfBlank() ?: resolvedValue,
            description = description.nullIfBlank()
        )
    }
}

private fun toAlternativeConfigOption(
    id: AcpSessionConfigId,
    currentValue: String?,
    entries: List<AcpAlternativeChoicePayload>
): AcpConfigOption? {
    val options = entries.mapNotNull(AcpAlternativeChoicePayload::toChoice)
    val resolvedCurrentValue = currentValue.nullIfBlank()
    if (options.isEmpty() && resolvedCurrentValue == null) {
        return null
    }
    return AcpConfigOption(
        id = id.value,
        name = id.displayName,
        category = id.value,
        type = "select",
        currentValue = resolvedCurrentValue,
        options = options
    )
}

private fun firstNotBlank(vararg values: String?): String? {
    return values.firstNotNullOfOrNull(String?::nullIfBlank)
}

private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

private fun Throwable.isMethodNotFoundJsonRpcError(): Boolean {
    return (this as? AcpJsonRpcException)?.error?.code == AcpJsonRpcError.METHOD_NOT_FOUND_CODE
}
