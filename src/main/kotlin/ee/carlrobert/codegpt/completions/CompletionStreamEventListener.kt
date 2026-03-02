package ee.carlrobert.codegpt.completions

interface CompletionStreamEventListener {
    fun onOpen() {}

    fun onMessage(message: String)

    fun onComplete(messageBuilder: StringBuilder)

    fun onCancelled(messageBuilder: StringBuilder)

    fun onError(error: CompletionError, ex: Throwable)
}
