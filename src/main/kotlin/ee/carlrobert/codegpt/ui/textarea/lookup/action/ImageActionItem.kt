package ee.carlrobert.codegpt.ui.textarea.lookup.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.ui.textarea.UserInputPanel
import ee.carlrobert.codegpt.ui.textarea.header.tag.ImageTagDetails
import ee.carlrobert.codegpt.ui.textarea.header.tag.TagManager

/**
 * Action item for attaching images to chat messages.
 * Opens a file chooser dialog allowing users to select image files.
 */
class ImageActionItem(
    private val project: Project,
    private val tagManager: TagManager
) : AbstractLookupActionItem() {

    companion object {
        private val logger = thisLogger()
        private val SUPPORTED_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "svg")
    }

    override val displayName = CodeGPTBundle.get("suggestionActionItem.attachImage.displayName")
    override val icon = AllIcons.FileTypes.Image

    override fun execute(project: Project, userInputPanel: UserInputPanel) {
        val descriptor = createImageFileDescriptor()

        try {
            val selectedFiles = FileChooser.chooseFiles(descriptor, project, null)
            handleSelectedFiles(selectedFiles, project, userInputPanel)
        } catch (e: Exception) {
            logger.error("Failed to open file chooser for image attachment", e)
        }
    }
    
    private fun createImageFileDescriptor(): FileChooserDescriptor {
        return FileChooserDescriptor(true, false, false, false, false, false).apply {
            title = displayName
            description = CodeGPTBundle.get("suggestionActionItem.attachImage.description")
            withFileFilter { file ->
                file.extension?.lowercase() in SUPPORTED_IMAGE_EXTENSIONS
            }
        }
    }
    
    private fun handleSelectedFiles(
        files: Array<com.intellij.openapi.vfs.VirtualFile>,
        project: Project,
        userInputPanel: UserInputPanel
    ) {
        if (files.isEmpty()) return
        
        val selectedFile = files.first()
        storeImagePath(project, selectedFile.path)
        userInputPanel.addTag(ImageTagDetails(selectedFile.path))
    }
    
    private fun storeImagePath(project: Project, path: String) {
        project.putUserData(CodeGPTKeys.IMAGE_ATTACHMENT_FILE_PATH, path)
    }
}