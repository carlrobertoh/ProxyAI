package ee.carlrobert.codegpt.codecompletions.edit

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.predictions.CodeSuggestionDiffViewer
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType.INCEPTION
import ee.carlrobert.codegpt.settings.service.ServiceType.PROXYAI
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.settings.service.inception.InceptionSettings

object NextEditCoordinator {

    fun requestNextEdit(
        editor: Editor,
        fileContent: String,
        caretOffset: Int = runReadAction { editor.caretModel.offset },
        addToQueue: Boolean = false,
    ) {
        val provider = service<ModelSelectionService>().getServiceForFeature(FeatureType.NEXT_EDIT)
        when (provider) {
            PROXYAI -> {
                if (!service<CodeGPTServiceSettings>().state.nextEditsEnabled) {
                    return
                }

                editor.project?.service<GrpcClientService>()?.getNextEdit(
                    editor,
                    fileContent,
                    caretOffset,
                    addToQueue
                )
            }

            INCEPTION -> {
                if (!service<InceptionSettings>().state.nextEditsEnabled) {
                    return
                }

                InceptionNextEditRunner.run(
                    editor,
                    fileContent,
                    caretOffset,
                    addToQueue
                ) { response ->
                    if (addToQueue) {
                        CodeGPTKeys.REMAINING_PREDICTION_RESPONSE.set(editor, response)
                    } else {
                        runInEdt {
                            if (editor.document.text == response.oldRevision) {
                                CodeSuggestionDiffViewer.displayInlineDiff(editor, response)
                            }
                        }
                    }
                }
            }

            else -> null
        }
    }
}