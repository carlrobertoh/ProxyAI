package ee.carlrobert.codegpt

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import ee.carlrobert.codegpt.actions.editor.EditorActionsUtil
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.configuration.ScreenshotPathDetector
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTService
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.AttachImageNotifier
import ee.carlrobert.codegpt.ui.OverlayUtil
import com.intellij.openapi.diagnostic.thisLogger
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

class CodeGPTProjectActivity : ProjectActivity {

    private val logger = thisLogger()

    override suspend fun execute(project: Project) {
        EditorActionsUtil.refreshActions()
        project.service<CodeGPTService>().syncUserDetailsAsync()

        if (!ApplicationManager.getApplication().isUnitTestMode
            && service<ConfigurationSettings>().state.checkForNewScreenshots
        ) {
            val configurationState = service<ConfigurationSettings>().state
            val watchPaths = configurationState.screenshotWatchPaths.ifEmpty {
                ScreenshotPathDetector.getDefaultPaths()
            }
            val watchExtensions = ScreenshotPathDetector.getDefaultFileExtensions().toSet()
            logger.debug("Screenshot watch configuration - paths: $watchPaths, extensions: $watchExtensions")
            val validPaths = watchPaths.filter { ScreenshotPathDetector.isValidWatchPath(it) }
            logger.debug("Valid watch paths after filtering: $validPaths")
            if (validPaths.isNotEmpty()) {
                logger.info("Starting screenshot file watching for ${validPaths.size} paths")
                project.service<FileWatcher>().watchMultiplePaths(validPaths) { fileName, watchPath ->
                    val fileExtension = getFileExtension(fileName)
                    logger.trace("File detected: fileName=$fileName, extension='$fileExtension', watchPath=$watchPath")
                    if (watchExtensions.contains(fileExtension)) {
                        val fullPath = Paths.get(watchPath).resolve(fileName).absolutePathString()
                        logger.info("New screenshot file created: $fullPath (extension='$fileExtension')")
                        showImageAttachmentNotification(project, fullPath)
                    } else {
                        logger.trace("File extension '$fileExtension' not in watch list: $watchExtensions")
                    }
                }
            } else {
                logger.warn("No valid screenshot watch paths found - screenshot detection disabled")
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
