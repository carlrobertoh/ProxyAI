package ee.carlrobert.codegpt.agent.history

import ai.koog.prompt.message.Message as PromptMessage
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val CACHEABLE_METADATA_KEY = "cacheable"

internal fun shouldHideInAgentToolWindow(
    message: PromptMessage.User,
    projectInstructions: String? = null
): Boolean {
    return isCacheableInstructionMessage(message) ||
            isProjectInstructionsMessage(message.content, projectInstructions)
}

private fun isCacheableInstructionMessage(message: PromptMessage.User): Boolean {
    val cacheable = message.metaInfo.metadata
        ?.get(CACHEABLE_METADATA_KEY)
        ?.jsonPrimitive
        ?: return false

    return cacheable.booleanOrNull ?: (cacheable.contentOrNull?.equals("true", ignoreCase = true) == true)
}

private fun isProjectInstructionsMessage(text: String, projectInstructions: String?): Boolean {
    if (projectInstructions.isNullOrBlank()) {
        return false
    }
    return normalize(text) == normalize(projectInstructions)
}

private fun normalize(value: String): String {
    return value.replace("\\s+".toRegex(), " ").trim()
}
