package ee.carlrobert.codegpt.completions

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ee.carlrobert.codegpt.settings.service.ServiceType
import javax.swing.JPanel

internal interface CompletionRunner {
    fun run(request: CompletionRunnerRequest): CancellableRequest
}

internal enum class StreamingMode {
    STREAMING,
    SINGLE_RESPONSE
}

internal sealed interface CompletionRunnerRequest {
    data class Streaming(
        val executor: PromptExecutor,
        val model: LLModel,
        val prompt: Prompt,
        val eventListener: CompletionStreamEventListener,
        val tools: List<ToolDescriptor> = emptyList(),
        val mode: StreamingMode = StreamingMode.STREAMING,
        val cancellationResultBuilder: (StringBuilder) -> StringBuilder = { StringBuilder(it) }
    ) : CompletionRunnerRequest

    data class Chat(
        val serviceType: ServiceType,
        val executor: PromptExecutor,
        val model: LLModel,
        val prompt: Prompt,
        val callParameters: ChatCompletionParameters,
        val eventListener: ChatStreamEventListener,
        val onToolCallUIUpdate: (JPanel) -> Unit = {}
    ) : CompletionRunnerRequest
}
