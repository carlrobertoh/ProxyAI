package ee.carlrobert.codegpt.agent.credits

import ai.koog.prompt.message.Message
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class CreditsSnapshot(
    val remaining: Long,
    val monthlyRemaining: Long,
    val total: Long,
)

fun extractCreditsSnapshot(responses: List<Message.Response>): CreditsSnapshot? {
    val creditsObject = responses.asReversed().firstNotNullOfOrNull { response ->
        response.metaInfo.metadata?.get("credits")?.jsonObject
    } ?: return null
    val remaining = creditsObject.longOrNull("remaining") ?: return null
    val monthlyRemaining = creditsObject.longOrNull("monthly_remaining") ?: remaining
    val total = creditsObject.longOrNull("total") ?: 0
    return CreditsSnapshot(remaining, monthlyRemaining, total)
}

private fun JsonObject.longOrNull(key: String): Long? {
    return this[key]?.jsonPrimitive?.longOrNull
}
