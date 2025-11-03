package ee.carlrobert.codegpt.util

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import ee.carlrobert.codegpt.EncodingManager

object RecentlyViewedFilesUtil {

    fun orderedFiles(
        project: Project?,
        currentFile: VirtualFile?,
        limit: Int = 3
    ): List<VirtualFile> {
        if (project == null) return emptyList()

        return runReadAction {
            val fileIndex = project.service<ProjectFileIndex>()
            val fileEditorManager = FileEditorManager.getInstance(project)
            val openFiles = fileEditorManager?.openFiles?.toList().orEmpty()
            val selectedFiles = fileEditorManager?.selectedFiles?.toList().orEmpty()
            val openFilesSet = openFiles.toSet()

            val historyOpen =
                runCatching { EditorHistoryManager.getInstance(project).fileList.toList() }
                    .getOrElse { emptyList() }
                    .filter { it in openFilesSet }

            (historyOpen + selectedFiles + openFiles)
                .asSequence()
                .filter { file ->
                    file != currentFile &&
                        !file.isDirectory &&
                        !file.fileType.isBinary &&
                        (fileIndex.isInSourceContent(file) || fileIndex.isInTestSourceContent(file))
                }
                .distinctBy { it.path }
                .take(limit)
                .toList()
        }
    }
}
