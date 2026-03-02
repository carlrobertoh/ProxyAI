package ee.carlrobert.codegpt.inlineedit.engine

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import ee.carlrobert.codegpt.CodeGPTBundle
import ee.carlrobert.codegpt.completions.AutoApplyParameters
import ee.carlrobert.codegpt.completions.AutoApplyService
import ee.carlrobert.codegpt.inlineedit.InlineEditSession
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
        val destination = FileDocumentManager.getInstance().getFile(ctx.editor.document) ?: return

        val updateSnippet = blocks.joinToString("\n// ... existing code ...\n\n") { it.trimEnd() }
        val original = ctx.editor.document.text

        coroutineScope.launch {
            runInEdt {
                ctx.inlay.setThinkingVisible(true, CodeGPTBundle.get("inlineEdit.applying"))
            }

            val merged = try {
                service<AutoApplyService>().applyCode(
                    AutoApplyParameters(
                        source = updateSnippet,
                        destination = destination
                    ),
                    original
                )
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
