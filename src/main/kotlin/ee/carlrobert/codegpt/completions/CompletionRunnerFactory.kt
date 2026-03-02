package ee.carlrobert.codegpt.completions

internal object CompletionRunnerFactory {
    fun create(request: CompletionRunnerRequest): CompletionRunner {
        return when (request) {
            is CompletionRunnerRequest.Chat ->
                if (shouldUseAgentRunner(request)) AgentCompletionRunner else ChatStreamingCompletionRunner
            is CompletionRunnerRequest.Streaming -> StreamingCompletionRunner
        }
    }

    private fun shouldUseAgentRunner(request: CompletionRunnerRequest.Chat): Boolean {
        if (request.callParameters.requestType != RequestType.NORMAL_REQUEST) {
            return true
        }
        return !request.callParameters.mcpTools.isNullOrEmpty() &&
                request.callParameters.toolApprovalMode != ToolApprovalMode.BLOCK_ALL
    }
}
