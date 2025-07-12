package ee.carlrobert.codegpt.completions.llama.logging

object NoOpLoggingStrategy : ServerLoggingStrategy {
    override fun logMessage(message: String, isError: Boolean, isBuildLog: Boolean) {
        // No-op: silent logging for headless operations
    }

    override fun setPhase(phase: String) {
        // No-op
    }

    override fun startProgress() {
        // No-op
    }

    override fun stopProgress() {
        // No-op
    }
}