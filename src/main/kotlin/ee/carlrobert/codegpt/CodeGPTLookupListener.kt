package ee.carlrobert.codegpt

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupEvent
import com.intellij.codeInsight.lookup.LookupListener
import com.intellij.codeInsight.lookup.LookupManagerListener
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorKind
import ee.carlrobert.codegpt.predictions.PredictionService
import ee.carlrobert.codegpt.settings.GeneralSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings
import ee.carlrobert.codegpt.ui.OverlayUtil

class CodeGPTLookupListener : LookupManagerListener {
    override fun activeLookupChanged(oldLookup: Lookup?, newLookup: Lookup?) {
        if (newLookup is LookupImpl) {
            newLookup.addLookupListener(object : LookupListener {

                var beforeApply: String = ""
                var cursorOffset: Int = 0

                override fun beforeItemSelected(event: LookupEvent): Boolean {
                    beforeApply = newLookup.editor.document.text
                    cursorOffset = runReadAction {
                        newLookup.editor.caretModel.offset
                    }

                    return true
                }

                override fun itemSelected(event: LookupEvent) {
                    val editor = newLookup.editor
                    if (GeneralSettings.getSelectedService() != ServiceType.CODEGPT
                        || !service<CodeGPTServiceSettings>().state.nextEditsEnabled
                        || editor.editorKind != EditorKind.MAIN_EDITOR
                    ) {
                        return
                    }

                    val settings = service<CodeGPTServiceSettings>().state
                    if (settings.codeCompletionSettings.codeCompletionsEnabled) {
                        settings.codeCompletionSettings.codeCompletionsEnabled = false
                        OverlayUtil.showNotification(
                            "Code completions and multi-line edits cannot be active simultaneously.",
                            NotificationType.WARNING
                        )
                    }

                    ApplicationManager.getApplication().executeOnPooledThread {
                        service<PredictionService>().displayInlineDiff(editor)
                    }
                }
            })
        }
    }
}