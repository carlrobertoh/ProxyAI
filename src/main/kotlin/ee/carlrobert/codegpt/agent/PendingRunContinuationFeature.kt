package ee.carlrobert.codegpt.agent

import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.context.AgentContextData
import ai.koog.agents.core.agent.context.RollbackStrategy
import ai.koog.agents.core.agent.context.store
import ai.koog.agents.core.agent.entity.AIAgentStorageKey
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.feature.AIAgentGraphFeature
import ai.koog.agents.core.feature.config.FeatureConfig
import ai.koog.agents.core.feature.pipeline.AIAgentGraphPipeline
import ai.koog.prompt.message.Message
import ai.koog.serialization.JSONElement
import ai.koog.serialization.typeToken
import ee.carlrobert.codegpt.agent.strategy.SINGLE_RUN_NODE_CALL_LLM
import java.util.concurrent.ConcurrentHashMap

internal data class PendingRunContinuation(
    val messageHistory: List<Message>,
    val input: MessageWithContext
)

internal class PendingRunContinuationConfig : FeatureConfig() {
    lateinit var sessionId: String
    lateinit var pendingContinuations: ConcurrentHashMap<String, PendingRunContinuation>
}

@OptIn(InternalAgentsApi::class)
internal object PendingRunContinuationFeature :
    AIAgentGraphFeature<PendingRunContinuationConfig, Unit> {

    override val key = AIAgentStorageKey<Unit>("proxyai-pending-run-continuation")

    override fun createInitialConfig(agentConfig: AIAgentConfig): PendingRunContinuationConfig {
        return PendingRunContinuationConfig()
    }

    override fun install(config: PendingRunContinuationConfig, pipeline: AIAgentGraphPipeline) {
        pipeline.interceptStrategyStarting(this) { event ->
            val continuation = config.pendingContinuations.remove(config.sessionId) ?: return@interceptStrategyStarting
            val serializedInput = serializeInput(event.context.config, continuation.input)
            event.context.store(
                AgentContextData(
                    messageHistory = continuation.messageHistory,
                    nodePath = "${event.context.agentId}/single_run/$SINGLE_RUN_NODE_CALL_LLM",
                    lastInput = serializedInput,
                    rollbackStrategy = RollbackStrategy.MessageHistoryOnly
                )
            )
        }
    }

    private fun serializeInput(config: AIAgentConfig, input: MessageWithContext): JSONElement {
        return config.serializer.encodeToJSONElement(input, typeToken<MessageWithContext>())
    }
}
