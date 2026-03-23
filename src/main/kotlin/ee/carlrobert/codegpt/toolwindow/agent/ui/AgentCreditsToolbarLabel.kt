package ee.carlrobert.codegpt.toolwindow.agent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelChangeNotifier
import ee.carlrobert.codegpt.settings.service.ModelChangeNotifierAdapter
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTService
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTUserDetails
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTUserDetailsNotifier
import ee.carlrobert.codegpt.toolwindow.agent.AgentSession
import ee.carlrobert.codegpt.toolwindow.agent.AgentCreditsEvent
import ee.carlrobert.codegpt.toolwindow.agent.AgentCreditsListener
import ee.carlrobert.codegpt.toolwindow.agent.AgentUiStateNotifier
import java.text.NumberFormat
import java.util.*

class AgentCreditsToolbarLabel(
    private val project: Project,
    private val sessionProvider: () -> AgentSession?
) : JBLabel(), Disposable {

    private val numberFormat = NumberFormat.getNumberInstance(Locale.US).apply {
        isGroupingUsed = true
    }
    private val messageBusConnection: MessageBusConnection = project.messageBus.connect(this)
    private var currentCredits: AgentCreditsEvent? = null
    private var currentUserDetails: CodeGPTUserDetails? = null
    private var userDetailsRequested: Boolean = false

    init {
        isOpaque = false
        font = JBFont.small()
        border = JBUI.Borders.empty(0, 6, 0, 6)
        currentUserDetails = project.getUserData(CodeGPTKeys.CODEGPT_USER_DETAILS)
        subscribeToUpdates()
        updateDisplay()
    }

    private fun subscribeToUpdates() {
        messageBusConnection.subscribe(
            CodeGPTUserDetailsNotifier.CODEGPT_USER_DETAILS_TOPIC,
            object : CodeGPTUserDetailsNotifier {
                override fun userDetailsObtained(userDetails: CodeGPTUserDetails?) {
                    currentUserDetails = userDetails
                    updateDisplay()
                }
            }
        )
        messageBusConnection.subscribe(
            AgentCreditsListener.AGENT_CREDITS_TOPIC,
            object : AgentCreditsListener {
                override fun onCreditsChanged(event: AgentCreditsEvent) {
                    currentCredits = event
                    updateDisplay()
                }
            }
        )
        messageBusConnection.subscribe(
            ModelChangeNotifier.getTopic(),
            object : ModelChangeNotifierAdapter() {
                override fun agentModelChanged(newModel: String, serviceType: ServiceType) {
                    updateDisplay()
                }
            }
        )
        messageBusConnection.subscribe(
            AgentUiStateNotifier.AGENT_UI_STATE_TOPIC,
            object : AgentUiStateNotifier {
                override fun activeSessionChanged() {
                    updateDisplay()
                }

                override fun sessionRuntimeChanged(sessionId: String) {
                    updateDisplay()
                }
            }
        )
    }

    fun refresh() {
        updateDisplay()
    }

    private fun updateDisplay() {
        ApplicationManager.getApplication().invokeLater {
            val activeSession = sessionProvider()
            val provider = ModelSettings.getInstance()
                .getServiceForFeature(FeatureType.AGENT)
            val isExternalAgentSelected = !activeSession?.externalAgentId.isNullOrBlank()
            if (provider != ServiceType.PROXYAI || isExternalAgentSelected) {
                text = null
                toolTipText = null
                isVisible = false
                return@invokeLater
            }

            isVisible = true
            if (!userDetailsRequested && currentUserDetails == null) {
                userDetailsRequested = true
                project.getService(CodeGPTService::class.java).syncUserDetailsAsync()
            }
            val credits = currentCredits
            val userDetails = currentUserDetails
            val userTotal = userDetails?.creditsTotal
            val userRemaining = if (userTotal != null && userDetails?.creditsUsed != null) {
                (userTotal - userDetails.creditsUsed).coerceAtLeast(0)
            } else null
            val remaining = credits?.remaining ?: userRemaining
            val labelPrefix = CodeGPTBundle.get("agent.credits.label")
            val remainingText = remaining?.let {
                CodeGPTBundle.get("agent.credits.remainingValue", numberFormat.format(it))
            } ?: "--"

            text = "$labelPrefix: $remainingText"

            toolTipText = buildString {
                append("<html><body>")
                append("<b>$labelPrefix</b><br>")
                if (remaining != null) {
                    append(
                        CodeGPTBundle.get("agent.credits.tooltip.remaining", numberFormat.format(remaining))
                    )
                    append("<br>")
                }
                if (userTotal != null) {
                    append(CodeGPTBundle.get("agent.credits.tooltip.total", numberFormat.format(userTotal)))
                    append("<br>")
                }
                append("</body></html>")
            }
        }
    }

    override fun dispose() {
    }
}
