package ee.carlrobert.codegpt.agent.external

internal enum class AcpToolCallStatus(val wireValue: String) {
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    val isTerminal: Boolean
        get() = this != IN_PROGRESS
}
