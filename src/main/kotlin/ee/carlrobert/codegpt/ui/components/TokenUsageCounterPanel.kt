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
import ee.carlrobert.codegpt.settings.models.ModelSettings
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
    private var lastEstimatedPromptTokens: Long = 0L
    private var calibrationOffset: Long = 0L
    private var lastReportedSizeTokens: Long? = null
    private var lastReportedCostAmount: Double? = null
    private var lastReportedCostCurrency: String? = null
    private var hasReportedUsage: Boolean = false

    init {
        Disposer.register(ApplicationManager.getApplication(), scope)

        isOpaque = false
        font = JBFont.small()
        border = JBUI.Borders.empty(5, 7)
        isVisible = false
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
                            val model = getAgentModelForSession(event.sessionId)
                                ?: getSelectedAgentModel()
                            hasReportedUsage = true
                            isVisible = true
                            lastReportedSizeTokens = event.sizeTokens?.takeIf { it > 0L }
                            lastReportedCostAmount = event.costAmount
                            lastReportedCostCurrency = event.costCurrency?.takeIf { it.isNotBlank() }
                            calibrationOffset = event.totalTokens - lastEstimatedPromptTokens
                            updateDisplay(event.totalTokens, model)
                        }
                    }
                })
        }
    }

    fun updateFromTotalTokens(totalTokens: Long) {
        if (!hasReportedUsage) {
            return
        }
        lastEstimatedPromptTokens = totalTokens
        val model = getSelectedAgentModel()
        val calibratedTotal = (totalTokens + calibrationOffset).coerceAtLeast(0L)
        updateDisplay(calibratedTotal, model)
    }

    fun hasReportedUsage(): Boolean = hasReportedUsage

    private fun updateDisplay(totalTokens: Long, model: LLModel?) {
        scope.launch {
            withEdt {
                updateColorAndText(totalTokens, model)
                updateTooltipText(totalTokens, model)
                revalidate()
                repaint()
            }
        }
    }

    private fun updateColorAndText(usedPromptTokens: Long, model: LLModel?) {
        val effectiveCapacity = (
            lastReportedSizeTokens
                ?: model?.let { computeBudget(it).inputBudget }
                ?: return
            ).coerceAtLeast(1L)
        val percentageUsed =
            ((usedPromptTokens.coerceAtLeast(0).toDouble() / effectiveCapacity.toDouble()) * 100.0)
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

    private fun updateTooltipText(totalTokens: Long, model: LLModel?) {
        toolTipText = buildString {
            append("<html><body>")
            append("<b>Usage Details</b><br>")
            append("Current usage: ${numberFormat.format(totalTokens)} tokens<br>")
            lastReportedSizeTokens?.let {
                append("Reported context size: ${numberFormat.format(it)} tokens<br>")
                append(
                    "Context remaining: ${
                        numberFormat.format((it - totalTokens).coerceAtLeast(0L))
                    } tokens<br>"
                )
            }
            if (lastReportedSizeTokens == null) {
                model?.let {
                    val budget = computeBudget(it)
                    append("Max output size: ${numberFormat.format(budget.reservedOutput)} tokens<br>")
                    append("Max context size: ${numberFormat.format(budget.contextLength)} tokens<br>")
                }
            }
            if (lastReportedCostAmount != null && lastReportedCostCurrency != null) {
                append(
                    "Reported cost: ${
                        numberFormat.format(lastReportedCostAmount)
                    } ${lastReportedCostCurrency}<br>"
                )
            }
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
        val contextLength = model.contextLength ?: 128_000L
        val configured =
            model.maxOutputTokens ?: ConfigurationSettings.getState().maxTokens.toLong()
        val reserved = configured
            .coerceAtLeast(0L)
            .coerceAtMost(contextLength)
        val inputBudget = (contextLength - reserved).coerceAtLeast(1L)
        return Budget(contextLength, reserved, inputBudget)
    }

    private fun getSelectedAgentModel(): LLModel? {
        return runCatching { ModelSettings.getInstance().getAgentModel() }.getOrNull()
    }
}
