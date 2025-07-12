package ee.carlrobert.codegpt.completions.llama.logging

interface ServerLoggingStrategy {
    fun logMessage(message: String, isError: Boolean, isBuildLog: Boolean = false)
    fun setPhase(phase: String)
    fun startProgress()
    fun stopProgress()
}