package ee.carlrobert.codegpt.inlineedit.engine

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.util.TextRange
import ee.carlrobert.codegpt.completions.CompletionClientProvider
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.inlineedit.InlineEditSession
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.llm.client.codegpt.request.AutoApplyRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ee.carlrobert.codegpt.util.MarkdownUtil

class ProxyAIApplyStrategy : ApplyStrategy {

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun apply(ctx: ApplyContext) {
        val blocks = MarkdownUtil.extractCodeBlocks(ctx.lastAssistantResponse)
        if (blocks.isEmpty()) return

        val modelSelection =
            ModelSelectionService.getInstance().getModelSelectionForFeature(FeatureType.AUTO_APPLY)
        if (modelSelection.provider != ServiceType.PROXYAI) return

        val updateSnippet = blocks.joinToString("\n// ... existing code ...\n\n") { it.trimEnd() }
        val original = ctx.editor.document.text

        coroutineScope.launch {
            runInEdt {
                ctx.inlay.setThinkingVisible(true, CodeGPTBundle.get("inlineEdit.applying"))
            }

            val merged = try {
                CompletionClientProvider.getCodeGPTClient()
                    .applyChanges(AutoApplyRequest(modelSelection.model, original, updateSnippet))
                    .mergedCode
            } catch (_: Exception) {
                null
            }

            if (merged.isNullOrBlank()) {
                runInEdt {
                    ctx.inlay.setThinkingVisible(false)
                }
                return@launch
            }

            runInEdt {
                val baseRange = TextRange(0, ctx.editor.document.textLength)
                InlineEditSession.start(
                    requireNotNull(ctx.editor.project),
                    ctx.editor,
                    baseRange,
                    merged
                )
                ctx.inlay.setInlineEditControlsVisible(true)
                ctx.inlay.setThinkingVisible(false)
                ctx.inlay.hideAskPopup()
            }
        }
    }
}
