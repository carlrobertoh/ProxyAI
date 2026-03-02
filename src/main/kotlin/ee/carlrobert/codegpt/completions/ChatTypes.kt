package ee.carlrobert.codegpt.completions

data class ChatError @JvmOverloads constructor(
    val message: String,
    val code: String? = null
)

data class ChatToolFunction(
    val name: String? = null,
    val arguments: String? = null
)

data class ChatToolCall(
    val index: Int? = null,
    val id: String = "",
    val type: String = "function",
    val function: ChatToolFunction = ChatToolFunction()
)

fun interface CancellableRequest {
    fun cancel()
}

interface ChatStreamEventListener {
    fun onOpen() {}

    fun onEvent(data: String) {}

    fun onMessage(message: String)

    fun onComplete(messageBuilder: StringBuilder)

    fun onCancelled(messageBuilder: StringBuilder)

    fun onError(error: ChatError, ex: Throwable)
}
