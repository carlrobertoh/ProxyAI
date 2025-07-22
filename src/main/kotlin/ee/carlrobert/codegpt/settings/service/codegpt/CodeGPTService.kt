package ee.carlrobert.codegpt.settings.service.codegpt

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.CodeGPTKeys.CODEGPT_USER_DETAILS
import ee.carlrobert.codegpt.completions.CompletionClientProvider
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.CodeGptApiKey
import ee.carlrobert.codegpt.credentials.CredentialsStore.getCredential
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.service.ModelReplacementDialog
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTUserDetailsNotifier.Companion.CODEGPT_USER_DETAILS_TOPIC
import kotlinx.coroutines.*
import javax.swing.SwingUtilities

@Service(Service.Level.PROJECT)
class CodeGPTService private constructor(val project: Project) {

    companion object {
        private val logger = thisLogger()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun syncUserDetailsAsync() {
        syncUserDetailsAsync(getCredential(CodeGptApiKey))
    }

    fun syncUserDetailsAsync(apiKey: String?, showDialog: Boolean = false) {
        serviceScope.launch {
            try {
                val userDetails = withContext(Dispatchers.IO) {
                    if (apiKey.isNullOrEmpty()) null
                    else CompletionClientProvider.getCodeGPTClient().getUserDetails(apiKey)
                }
                if (userDetails != null && userDetails.pricingPlan != null) {
                    if (!userDetails.fullName.isNullOrEmpty()) {
                        service<GeneralSettings>().state.run {
                            displayName = userDetails.fullName
                            avatarBase64 = userDetails.avatarBase64 ?: ""
                        }
                    }
                }

                CODEGPT_USER_DETAILS.set(project, userDetails)

                if (showDialog) {
                    SwingUtilities.invokeLater {
                        ModelReplacementDialog.showDialogIfNeeded(ServiceType.PROXYAI)
                    }
                }
                project.messageBus
                    .syncPublisher<CodeGPTUserDetailsNotifier>(CODEGPT_USER_DETAILS_TOPIC)
                    .userDetailsObtained(userDetails)
            } catch (ex: Exception) {
                logger.warn(ex)
            }
        }
    }
}