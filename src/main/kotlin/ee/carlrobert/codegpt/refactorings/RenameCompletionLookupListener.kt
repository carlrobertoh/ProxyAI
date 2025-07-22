package ee.carlrobert.codegpt.refactorings

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupManagerListener
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import ee.carlrobert.codegpt.CodeGPTKeys.IS_PROMPT_TEXT_FIELD_DOCUMENT
import ee.carlrobert.codegpt.Icons
import ee.carlrobert.codegpt.completions.CompletionRequestService
import ee.carlrobert.codegpt.completions.LookupCompletionParameters
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.ui.OverlayUtil
import ee.carlrobert.llm.client.codegpt.response.CodeGPTException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RenameCompletionLookupListener : LookupManagerListener {

    companion object {
        private val logger = thisLogger()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun activeLookupChanged(oldLookup: Lookup?, newLookup: Lookup?) {
        if (newLookup !is LookupImpl) {
            return
        }

        val isPromptTextFieldDocument =
            IS_PROMPT_TEXT_FIELD_DOCUMENT[newLookup.editor.document] ?: false
        val featureEnabled = service<ConfigurationSettings>().state.methodNameGenerationEnabled
        if (isPromptTextFieldDocument
            || !featureEnabled
            || !CompletionRequestService.isRequestAllowed(FeatureType.CHAT)
        ) {
            return
        }

        newLookup.psiElement?.context?.let { context ->
            val renamingEnabled = context.getUserData(DefaultNameSuggestionProvider.KEY) ?: false
            if (renamingEnabled) {
                val selection = runReadAction { context.text }
                scope.launch {
                    try {
                        val lookupCompletion = service<CompletionRequestService>()
                            .getLookupCompletion(LookupCompletionParameters(selection))
                        if (lookupCompletion.isNotEmpty()) {
                            addCompletionLookupValues(newLookup, lookupCompletion)
                        }
                    } catch (ex: CodeGPTException) {
                        OverlayUtil.showNotification(ex.detail, NotificationType.ERROR)
                    } catch (ex: Exception) {
                        logger.error(
                            "Something went wrong while requesting completion lookup values.",
                            ex
                        )
                    }
                }
            }
        }
    }

    private fun addCompletionLookupValues(lookup: LookupImpl, response: String) {
        val values = response.split(",".toRegex())
            .map { it.trim() }
            .dropLastWhile { it.isEmpty() }
            .toTypedArray()

        runInEdt {
            for (value in values) {
                val prioritizedLookupElement = PrioritizedLookupElement.withPriority(
                    LookupElementBuilder
                        .create(value)
                        .withIcon(Icons.DefaultSmall)
                        .withLookupString(value),
                    1.0
                )
                if (!lookup.isLookupDisposed) {
                    lookup.addItem(prioritizedLookupElement, PrefixMatcher.ALWAYS_TRUE)
                }
            }
            if (!lookup.isLookupDisposed) {
                lookup.refreshUi(true, true)
            }
        }
    }
}
