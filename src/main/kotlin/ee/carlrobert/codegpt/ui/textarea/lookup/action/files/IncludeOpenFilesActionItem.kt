package ee.carlrobert.codegpt.ui.textarea.lookup.action.files

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.settings.ProxyAISettingsService
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.FileTagDetails
import ee.carlrobert.codegpt.ui.textarea.lookup.action.AbstractLookupActionItem
import javax.swing.Icon

class IncludeOpenFilesActionItem : AbstractLookupActionItem() {
    override val displayName: String =
        CodeGPTBundle.get("suggestionActionItem.includeOpenFiles.displayName")
    override val icon: Icon = Icons.ListFiles

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        val fileTags = userInputPanel.getSelectedTags().filterIsInstance<FileTagDetails>()
        val settingsService = project.service<ProxyAISettingsService>()
        project.service<FileEditorManager>().openFiles
            .filter { openFile ->
                settingsService.isVirtualFileVisible(openFile) &&
                        fileTags.none { it.virtualFile == openFile }
            }
            .forEach {
                userInputPanel.addTag(FileTagDetails(it))
            }
    }
}
