package ee.carlrobert.codegpt

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import ee.carlrobert.codegpt.actions.editor.EditorActionsUtil
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTService
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.AttachImageNotifier
import ee.carlrobert.codegpt.ui.OverlayUtil
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

class CodeGPTProjectActivity : ProjectActivity {

    private val watchExtensions = setOf("jpg", "jpeg", "png")

    override suspend fun execute(project: Project) {
        EditorActionsUtil.refreshActions()

        val settings = service<GeneralSettings>().state
        if (settings.selectedService == ServiceType.CODEGPT) {
            project.service<CodeGPTService>().syncUserDetailsAsync()
        }

        if (!ApplicationManager.getApplication().isUnitTestMode
            && service<ConfigurationSettings>().state.checkForNewScreenshots
        ) {
            val desktopPath = Paths.get(System.getProperty("user.home"), "Desktop")
            project.service<FileWatcher>().watch(desktopPath) {
                if (watchExtensions.contains(getFileExtension(it))) {
                    showImageAttachmentNotification(
                        project,
                        desktopPath.resolve(it).absolutePathString()
                    )
                }
            }
        }
    }

    private fun getFileExtension(path: Path): String {
        val fileName = path.fileName.toString()
        val lastIndexOfDot = fileName.lastIndexOf('.')
        return if (lastIndexOfDot != -1) {
            fileName.substring(lastIndexOfDot + 1).lowercase()
        } else {
            ""
        }
    }

    private fun showImageAttachmentNotification(project: Project, filePath: String) {
        OverlayUtil.getDefaultNotification(
            CodeGPTBundle.get("imageAttachmentNotification.content"),
            NotificationType.INFORMATION
        )
            .addAction(NotificationAction.createSimpleExpiring(
                CodeGPTBundle.get("imageAttachmentNotification.action")
            ) {
                CodeGPTKeys.IMAGE_ATTACHMENT_FILE_PATH.set(project, filePath)
                project.messageBus
                    .syncPublisher<AttachImageNotifier>(
                        AttachImageNotifier.IMAGE_ATTACHMENT_FILE_PATH_TOPIC
                    )
                    .imageAttached(filePath)
            })
            .addAction(NotificationAction.createSimpleExpiring(
                CodeGPTBundle.get("shared.notification.doNotShowAgain")
            ) {
                service<ConfigurationSettings>().state.checkForNewScreenshots = false
            })
            .notify(project)
    }
}
