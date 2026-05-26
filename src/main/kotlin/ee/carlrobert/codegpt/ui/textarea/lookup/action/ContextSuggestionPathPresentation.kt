package ee.carlrobert.codegpt.ui.textarea.lookup.action

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.configuration.ContextSuggestionPathDetailsMode

internal fun contextSuggestionTypeText(project: Project, file: VirtualFile): String? {
    val projectDir = project.guessProjectDir()
    return when (ConfigurationSettings.getState().contextSuggestionSettings.pathDetailsMode) {
        ContextSuggestionPathDetailsMode.FULL_PATH -> projectRelativePath(projectDir, file) ?: file.path
        ContextSuggestionPathDetailsMode.DIRECTORY_ONLY -> parentDirectoryPath(projectDir, file)
    }
}

private fun parentDirectoryPath(projectDir: VirtualFile?, file: VirtualFile): String? {
    val parent = file.parent ?: return null
    return if (projectDir != null) {
        val relativeParentPath = VfsUtil.getRelativePath(parent, projectDir) ?: return parent.path
        if (relativeParentPath.isEmpty()) "." else relativeParentPath
    } else {
        parent.path
    }
}

private fun projectRelativePath(projectDir: VirtualFile?, file: VirtualFile): String? {
    if (projectDir == null) {
        return null
    }
    return VfsUtil.getRelativePath(file, projectDir)
}
