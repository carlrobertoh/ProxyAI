package ee.carlrobert.codegpt.completions

abstract class BaseChatStreamEventListener(
    protected val callParameters: ChatCompletionParameters,
    protected val wrappedListener: CompletionResponseEventListener,
    private val syncMessageResponse: Boolean = true
) : ChatStreamEventListener {

    protected val messageBuilder = StringBuilder()

    override fun onOpen() {
        wrappedListener.handleRequestOpen()
    }

    override fun onMessage(message: String) {
        messageBuilder.append(message)
        if (syncMessageResponse) {
            callParameters.message.response = messageBuilder.toString()
        }
        wrappedListener.handleMessage(message)
    }

}
