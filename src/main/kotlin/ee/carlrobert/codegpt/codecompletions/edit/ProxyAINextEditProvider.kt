package ee.carlrobert.codegpt.codecompletions.edit

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings

class ProxyAINextEditProvider : NextEditProvider {
    override fun request(editor: Editor, fileContent: String, caretOffset: Int, addToQueue: Boolean) {
        if (service<ModelSelectionService>().getServiceForFeature(FeatureType.NEXT_EDIT) != ServiceType.PROXYAI
            || !service<CodeGPTServiceSettings>().state.nextEditsEnabled
        ) {
            return
        }
        editor.project?.service<GrpcClientService>()?.cancelNextEdit()
        editor.project?.service<GrpcClientService>()?.getNextEdit(
            editor,
            fileContent,
            caretOffset,
            addToQueue
        )
    }
}
