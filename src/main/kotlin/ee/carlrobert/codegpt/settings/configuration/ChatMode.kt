package ee.carlrobert.codegpt.settings.configuration

enum class ChatMode(
    val displayName: String,
    val description: String,
    val isEnabled: Boolean = true
) {
    ASK(
        displayName = "Ask",
        description = "Conversational responses with explanations"
    ),
    EDIT(
        displayName = "Edit",
        description = "Code modifications with search/replace blocks"
    ),
    AGENT(
        displayName = "Agent (soon)",
        description = "AI agent with tool access and reasoning",
        isEnabled = false
    );
}