package ee.carlrobert.codegpt.conversations.message

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class QueuedMessage(
    var prompt: String,
    val id: UUID = UUID.randomUUID(),
    var timestamp: Long = System.currentTimeMillis()
)