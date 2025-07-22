package ee.carlrobert.codegpt.predictions

import com.intellij.diff.DiffManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.application
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.codecompletions.edit.GrpcClientService
import ee.carlrobert.codegpt.util.EditorDiffUtil.createDiffRequest
import kotlin.coroutines.cancellation.CancellationException

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

    fun displayInlineDiff(editor: Editor) {
        val project = editor.project ?: return
        try {
            application.executeOnPooledThread {
                CompletionProgressNotifier.update(project, true)
                project.service<GrpcClientService>().getNextEdit(
                    editor,
                    editor.document.text,
                    runReadAction { editor.caretModel.offset },
                )
            }
        } catch (e: CancellationException) {
            // ignore
        } catch (ex: Exception) {
            logger.error("Error communicating with server: ${ex.message}")
        }
    }

    fun showDiff(editor: Editor, nextRevision: String) {
        val project: Project = editor.project ?: return
        val tempDiffFile = LightVirtualFile(editor.virtualFile.name, nextRevision)
        val diffRequest = createDiffRequest(project, tempDiffFile, editor.virtualFile)
        runInEdt {
            service<DiffManager>().showDiff(project, diffRequest)
        }
    }
}