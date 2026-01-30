package ee.carlrobert.codegpt.agent

import kotlinx.serialization.Serializable

@Serializable
data class MessageWithContextSnapshot(
    val text: String,
    val tagNames: List<String>
)

fun MessageWithContext.toSnapshot(): MessageWithContextSnapshot {
    return MessageWithContextSnapshot(text, tags.map { it.name })
}
