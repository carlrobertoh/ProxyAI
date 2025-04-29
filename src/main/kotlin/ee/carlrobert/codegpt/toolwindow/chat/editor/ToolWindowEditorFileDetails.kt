package ee.carlrobert.codegpt.toolwindow.chat.editor

import com.intellij.openapi.vfs.VirtualFile

data class ToolWindowEditorFileDetails(val path: String, val virtualFile: VirtualFile? = null) {
}