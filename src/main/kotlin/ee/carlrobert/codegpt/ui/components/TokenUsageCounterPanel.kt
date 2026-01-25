package ee.carlrobert.codegpt.ui.components

import ai.koog.prompt.llm.LLModel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.agent.AgentService
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.toolwindow.agent.TokenUsageEvent
import ee.carlrobert.codegpt.toolwindow.agent.TokenUsageListener
import ee.carlrobert.codegpt.util.coroutines.DisposableCoroutineScope
import ee.carlrobert.codegpt.util.coroutines.withEdt
import java.text.NumberFormat
import java.util.*

class TokenUsageCounterPanel(
    private val project: Project? = null,
    private val sessionId: String
) : JBLabel() {

    companion object {
        private val logger = thisLogger()
        private const val GREEN_MAX_USED = 30
        private const val ORANGE_MAX_USED = 60
        private const val AMBER_MAX_USED = 80
    }

    private val scope = DisposableCoroutineScope()
    private val numberFormat = NumberFormat.getNumberInstance(Locale.US).apply {
        isGroupingUsed = true
    }
    private var messageBusConnection: MessageBusConnection? = null
    private var currentSessionId: String = sessionId

    init {
        Disposer.register(ApplicationManager.getApplication(), scope)

        isOpaque = false
        font = JBFont.small()
        border = JBUI.Borders.empty(5, 7)
        setupMessageBusConnection()
    }

    private fun setupMessageBusConnection() {
        project?.let { proj ->
            messageBusConnection = proj.messageBus.connect()
            messageBusConnection?.subscribe(
                TokenUsageListener.TOKEN_USAGE_TOPIC,
                object : TokenUsageListener {
                    override fun onTokenUsageChanged(event: TokenUsageEvent) {
                        if (event.sessionId == sessionId) {
                            currentSessionId = event.sessionId
                            val model = getAgentModelForSession(event.sessionId) ?: return
                            updateDisplay(event, model)
                            updateTooltipText(event, model)
                        }
                    }
                })
        }
    }

    private fun updateDisplay(event: TokenUsageEvent, model: LLModel) {
        scope.launch {
            withEdt {
                updateColorAndText(event.totalTokens, model)
                revalidate()
                repaint()
            }
        }
    }

    private fun updateColorAndText(usedPromptTokens: Long, model: LLModel) {
        val budget = computeBudget(model)
        val percentageUsed =
            ((usedPromptTokens.coerceAtLeast(0).toDouble() / budget.inputBudget.toDouble()) * 100.0)
                .coerceIn(0.0, 100.0)
        val percentageLeft = (100.0 - percentageUsed).coerceIn(0.0, 100.0)

        foreground = when {
            percentageUsed < GREEN_MAX_USED -> JBColor(0x4CAF50, 0x66BB6A)
            percentageUsed < ORANGE_MAX_USED -> JBColor(0xFF9800, 0xFFB74D)
            percentageUsed < AMBER_MAX_USED -> JBColor(0xFF5722, 0xFF8A65)
            else -> JBColor(0xF44336, 0xEF5350)
        }

        text = "${percentageLeft.toInt()}% context left"
    }

    fun updateTooltipText(event: TokenUsageEvent, model: LLModel) {
        val budget = computeBudget(model)
        toolTipText = buildString {
            append("<html><body>")
            append("<b>Usage Details</b><br>")
            append("Input size: ${numberFormat.format(event.totalTokens)} tokens<br>")
            append("Max output size: ${numberFormat.format(budget.reservedOutput)} tokens<br>")
            append("Max context size: ${numberFormat.format(budget.contextLength)} tokens<br>")
            append("</body></html>")
        }
    }

    fun dispose() {
        messageBusConnection?.disconnect()
        scope.dispose()
    }

    private fun getAgentModelForSession(sessionId: String): LLModel? {
        return try {
            val agentService = project?.service<AgentService>() ?: return null
            return agentService.getAgentForSession(sessionId)?.agentConfig?.model
        } catch (e: Exception) {
            logger.warn("Failed to get max context size for session $sessionId", e)
            null
        }
    }

    private data class Budget(
        val contextLength: Long,
        val reservedOutput: Long,
        val inputBudget: Long,
    )

    private fun computeBudget(model: LLModel): Budget {
        val contextLength = model.contextLength
        val configured =
            (model.maxOutputTokens ?: ConfigurationSettings.getState().maxTokens).toLong()
        val reserved = configured
            .coerceAtLeast(0L)
            .coerceAtMost(contextLength)
        val inputBudget = (contextLength - reserved).coerceAtLeast(1L)
        return Budget(contextLength, reserved, inputBudget)
    }
}
