package ee.carlrobert.codegpt.completions.inception

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import ee.carlrobert.codegpt.completions.CompletionRequest

data class InceptionChatMessage(
    val role: String,
    val content: String,
)

data class InceptionApplyRequest(
    val model: String,
    val messages: List<InceptionChatMessage>,
    val stream: Boolean = false,
    val diffusing: Boolean = false,
) : CompletionRequest {
    class Builder {
        private var model: String = ""
        private var messages: List<InceptionChatMessage> = emptyList()
        private var stream: Boolean = false
        private var diffusing: Boolean = false

        fun setModel(model: String) = apply { this.model = model }
        fun setMessages(messages: List<InceptionChatMessage>) = apply { this.messages = messages }
        fun setStream(stream: Boolean) = apply { this.stream = stream }
        fun setDiffusing(diffusing: Boolean) = apply { this.diffusing = diffusing }

        fun build(): InceptionApplyRequest {
            return InceptionApplyRequest(model, messages, stream, diffusing)
        }
    }
}

data class InceptionNextEditRequest(
    val model: String,
    val messages: List<InceptionChatMessage>,
) : CompletionRequest {
    class Builder {
        private var model: String = ""
        private var messages: List<InceptionChatMessage> = emptyList()

        fun setModel(model: String) = apply { this.model = model }
        fun setMessages(messages: List<InceptionChatMessage>) = apply { this.messages = messages }

        fun build(): InceptionNextEditRequest {
            return InceptionNextEditRequest(model, messages)
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class InceptionChatCompletionResponse(
    val id: String? = null,
    val choices: List<InceptionChatCompletionChoice>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class InceptionChatCompletionChoice(
    val message: InceptionChatCompletionMessage? = null,
    val delta: InceptionChatCompletionMessage? = null,
    @JsonProperty("finish_reason")
    val finishReason: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class InceptionChatCompletionMessage(
    val role: String? = null,
    val content: String? = null,
)
