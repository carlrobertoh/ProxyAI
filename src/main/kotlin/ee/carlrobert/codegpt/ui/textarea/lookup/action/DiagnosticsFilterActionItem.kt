package ee.carlrobert.codegpt.ui.textarea.lookup.action

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.diagnostics.DiagnosticsFilter
import ee.carlrobert.codegpt.diagnostics.ProjectDiagnosticsService
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.DiagnosticsTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager

class DiagnosticsFilterActionItem(
    private val tagManager: TagManager,
    private val filter: DiagnosticsFilter
) : AbstractLookupActionItem() {

    override val displayName: String = filter.displayName
    override val icon = AllIcons.General.InspectionsEye

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        val diagnosticsService = project.service<ProjectDiagnosticsService>()
        val files = selectedContextFiles(userInputPanel.getSelectedTags())
        var matched = false

        files.forEach { virtualFile ->
            val diagnostics = diagnosticsService.collect(virtualFile, filter)
            if (!diagnostics.hasDiagnostics) {
                return@forEach
            }
            matched = true

            val newTag = DiagnosticsTagDetails(virtualFile, filter)
            val existing = tagManager.getTags()
                .filterIsInstance<DiagnosticsTagDetails>()
                .firstOrNull { it.virtualFile == virtualFile }

            when {
                existing == null -> {
                    userInputPanel.addTag(newTag)
                }

                existing != newTag -> {
                    tagManager.updateTag(existing, newTag)
                }
            }
        }

        if (!matched) {
            OverlayUtil.showNotification(
                filter.emptyMessage().removeSuffix(".") + " in selected context files.",
                NotificationType.INFORMATION
            )
        }
    }
}
