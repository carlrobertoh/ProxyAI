package ee.carlrobert.codegpt.nextedit

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType

object NextEditCoordinator {

    private val providers: Map<ServiceType, NextEditProvider> = mapOf(
        ServiceType.PROXYAI to ProxyAINextEditProvider(),
        ServiceType.INCEPTION to InceptionNextEditProvider()
    )

    fun requestNextEdit(
        editor: Editor,
        fileContent: String,
        caretOffset: Int = runReadAction { editor.caretModel.offset },
        addToQueue: Boolean = false,
    ) {
        val serviceType =
            service<ModelSelectionService>().getServiceForFeature(FeatureType.NEXT_EDIT)
        val provider = providers[serviceType] ?: return

        editor.project?.let { CompletionProgressNotifier.Companion.update(it, true) }
        provider.request(editor, fileContent, caretOffset, addToQueue)
    }
}