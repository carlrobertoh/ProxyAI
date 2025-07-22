package ee.carlrobert.codegpt.predictions

import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorAction
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler
import ee.carlrobert.codegpt.CodeGPTKeys.EDITOR_PREDICTION_DIFF_VIEWER

class OpenPredictionAction : EditorAction(Handler()), HintManagerImpl.ActionToIgnore {

    companion object {
        const val ID = "codegpt.openPrediction"
    }

    private class Handler : EditorWriteActionHandler() {

        override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
            val diffViewer = EDITOR_PREDICTION_DIFF_VIEWER.get(editor) ?: return
            val nextRevision = diffViewer.content2.document.text
            runInEdt {
                diffViewer.dispose()
            }
            service<PredictionService>().showDiff(editor, nextRevision)
        }
    }
}