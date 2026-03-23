package ee.carlrobert.codegpt.agent.external.acpcompat.vendor

import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.rpc.MethodName
import ee.carlrobert.codegpt.agent.external.ExternalAcpAgentPreset
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class AcpCompatibilityRegistry {

    fun initialProfile(preset: ExternalAcpAgentPreset): AcpPeerProfile {
        return profileForPresetId(preset.id)
    }

    fun resolveProfile(
        preset: ExternalAcpAgentPreset,
        agentInfo: AgentInfo?
    ): AcpPeerProfile {
        val presetProfile = initialProfile(preset)
        val implementationName = buildString {
            append(agentInfo?.implementation?.name.orEmpty())
            append(' ')
            append(agentInfo?.implementation?.title.orEmpty())
        }.trim().lowercase()

        return when {
            "codex" in implementationName -> AcpPeerProfile.CODEX
            "gemini" in implementationName -> AcpPeerProfile.GEMINI
            "opencode" in implementationName -> AcpPeerProfile.OPENCODE
            "claude" in implementationName -> AcpPeerProfile.CLAUDE_CODE
            else -> presetProfile
        }
    }

    fun augmentOutboundPayload(
        profile: AcpPeerProfile,
        methodName: MethodName,
        payload: JsonElement?,
        sessionRequestMeta: JsonElement? = null,
        launchEnv: Map<String, String> = emptyMap()
    ): JsonElement? {
        return when (methodName.name) {
            "initialize" -> augmentInitializeRequest(profile, payload)
            "authenticate" -> augmentAuthenticateRequest(profile, payload, launchEnv)
            "session/new", "session/load" -> mergeTopLevelMeta(payload, sessionRequestMeta)
            else -> payload
        }
    }

    fun normalizeInboundPayload(
        profile: AcpPeerProfile,
        methodName: MethodName,
        payload: JsonElement?
    ): JsonElement? {
        return when (methodName.name) {
            "initialize" -> normalizeInitializeResponse(profile, payload)
            "session/new", "session/load", "session/update" -> payload
            else -> payload
        }
    }

    private fun profileForPresetId(presetId: String): AcpPeerProfile {
        return when (presetId) {
            "codex" -> AcpPeerProfile.CODEX
            "gemini-cli" -> AcpPeerProfile.GEMINI
            "opencode" -> AcpPeerProfile.OPENCODE
            "claude-code" -> AcpPeerProfile.CLAUDE_CODE
            else -> AcpPeerProfile.STANDARD
        }
    }

    private fun augmentInitializeRequest(
        profile: AcpPeerProfile,
        payload: JsonElement?
    ): JsonElement? {
        val root = payload as? JsonObject ?: return payload
        if (!profile.supportsTerminalAuthMeta) {
            return payload
        }

        val capabilities = root["clientCapabilities"] as? JsonObject ?: return payload
        val capabilityMeta = mergeMeta(
            capabilities["_meta"],
            JsonObject(mapOf("terminal-auth" to JsonPrimitive(true)))
        ) ?: return payload

        return JsonObject(
            root + ("clientCapabilities" to JsonObject(capabilities + ("_meta" to capabilityMeta)))
        )
    }

    private fun augmentAuthenticateRequest(
        profile: AcpPeerProfile,
        payload: JsonElement?,
        launchEnv: Map<String, String>
    ): JsonElement? {
        if (profile != AcpPeerProfile.GEMINI) {
            return payload
        }

        val metaEntries = linkedMapOf<String, JsonElement>()
        val apiKey = launchEnv["GEMINI_API_KEY"] ?: launchEnv["GOOGLE_API_KEY"]
        val gateway = launchEnv["GEMINI_GATEWAY"]
        if (!apiKey.isNullOrBlank()) {
            metaEntries["api-key"] = JsonPrimitive(apiKey)
        }
        if (!gateway.isNullOrBlank()) {
            metaEntries["gateway"] = JsonPrimitive(gateway)
        }

        return mergeTopLevelMeta(
            payload,
            metaEntries.takeIf { it.isNotEmpty() }?.let(::JsonObject)
        )
    }

    private fun normalizeInitializeResponse(
        profile: AcpPeerProfile,
        payload: JsonElement?
    ): JsonElement? {
        if (profile != AcpPeerProfile.CODEX) {
            return payload
        }

        val root = payload as? JsonObject ?: return payload
        val authMethods = root["authMethods"]?.jsonArray ?: return payload
        val normalizedAuthMethods = authMethods.map(::normalizeAuthMethod)
        return JsonObject(root + ("authMethods" to kotlinx.serialization.json.JsonArray(normalizedAuthMethods)))
    }

    private fun normalizeAuthMethod(payload: JsonElement): JsonElement {
        val authMethod = payload as? JsonObject ?: return payload
        val type = authMethod["type"]?.jsonPrimitive?.contentOrNull
        if (type != "env_var" || authMethod["varName"] != null) {
            return payload
        }

        val firstVarName = authMethod["vars"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("name")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: return payload

        return JsonObject(authMethod + ("varName" to JsonPrimitive(firstVarName)))
    }

    private fun mergeTopLevelMeta(
        payload: JsonElement?,
        extraMeta: JsonElement?
    ): JsonElement? {
        val root = payload as? JsonObject ?: return payload
        val mergedMeta = mergeMeta(root["_meta"], extraMeta) ?: return payload
        return JsonObject(root + ("_meta" to mergedMeta))
    }

    private fun mergeMeta(
        base: JsonElement?,
        extra: JsonElement?
    ): JsonElement? {
        return when {
            base == null -> extra
            extra == null -> base
            base is JsonObject && extra is JsonObject -> JsonObject(base + extra)
            else -> extra
        }
    }
}
