package ee.carlrobert.codegpt.predictions

import com.intellij.diff.DiffManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.codecompletions.edit.GrpcClientService
import ee.carlrobert.codegpt.util.EditorDiffUtil.createDiffRequest

@Service
class PredictionService {

    companion object {
        private val logger = thisLogger()
    }

    fun acceptPrediction(editor: Editor) {
        val diffViewer = editor.getUserData(CodeGPTKeys.EDITOR_PREDICTION_DIFF_VIEWER)
        if (diffViewer != null && diffViewer.isVisible()) {
            diffViewer.applyChanges()
            return
        }
    }

    fun displayInlineDiff(
        editor: Editor,
        isManuallyOpened: Boolean = false
    ) {
        val project = editor.project ?: return
        try {
            CompletionProgressNotifier.update(project, true)
            project.service<GrpcClientService>().getNextEdit(editor, isManuallyOpened)
        } catch (ex: Exception) {
            logger.error("Error communicating with server: ${ex.message}")
        } finally {
            CompletionProgressNotifier.update(project, false)
        }
    }

    fun openDirectPrediction(editor: Editor, nextRevision: String) {
        val project: Project = editor.project ?: return
        val tempDiffFile = LightVirtualFile(editor.virtualFile.name, nextRevision)
        val diffRequest = createDiffRequest(project, tempDiffFile, editor)
        runInEdt {
            service<DiffManager>().showDiff(project, diffRequest)
        }
    }
}