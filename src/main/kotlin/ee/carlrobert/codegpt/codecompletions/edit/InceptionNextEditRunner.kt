package ee.carlrobert.codegpt.codecompletions.edit

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.EncodingManager
import ee.carlrobert.codegpt.completions.CompletionClientProvider
import ee.carlrobert.codegpt.settings.models.ModelRegistry
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.toolwindow.chat.parser.SseMessageParser
import ee.carlrobert.codegpt.toolwindow.chat.parser.SearchReplace
import ee.carlrobert.codegpt.util.GitUtil
import ee.carlrobert.service.NextEditResponse
import ee.carlrobert.llm.client.inception.request.InceptionNextEditRequest
import ee.carlrobert.llm.client.openai.completion.request.OpenAIChatCompletionStandardMessage
import java.util.*

object InceptionNextEditRunner {

    private val logger = thisLogger()

    fun isEnabled(): Boolean {
        return service<ModelSelectionService>().getServiceForFeature(FeatureType.NEXT_EDIT) == ServiceType.INCEPTION
    }

    fun run(editor: Editor, fileContent: String, caretOffset: Int, addToQueue: Boolean = false, onResult: (NextEditResponse) -> Unit) {
        try {
            val safeOffset = caretOffset.coerceIn(0, fileContent.length)
            val codeWithCursor = buildString {
                append(fileContent.substring(0, safeOffset))
                append("<|cursor|>")
                append(fileContent.substring(safeOffset))
            }

            val content = buildString {
                append("<|recently_viewed_code_snippets|>\n")
                append("<|/recently_viewed_code_snippets|>\n")
                append("<|current_file_content|>\n")
                append(fileContent)
                append("\n<|code_to_edit|>\n")
                append(codeWithCursor)
                append("\n<|/code_to_edit|>")
                append("<|/current_file_content|>\n")
                append("<|edit_diff_history|>\n")
                append(buildEditDiffHistory(editor.project))
                append("\n<|/edit_diff_history|>")
            }

            val message = OpenAIChatCompletionStandardMessage("user", content)
            val request = InceptionNextEditRequest.Builder()
                .setModel(ModelRegistry.MERCURY_CODER)
                .setMessages(listOf(message))
                .build()

            val response = CompletionClientProvider.getInceptionClient().getNextEditCompletion(request)
            val text = response.choices?.firstOrNull()?.message?.content ?: return

            val next = extractNextRevision(text, fileContent)
            val result = NextEditResponse.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setOldRevision(fileContent)
                .setNextRevision(next)
                .build()
            onResult(result)
        } catch (ex: Exception) {
            logger.error("Something went wrong while retrieving next edit completion", ex)
        }
    }

    private fun extractNextRevision(message: String, source: String): String {
        // If model returned a fenced full file, extract it directly
        extractTripleBacktickCode(message)?.let { fenced ->
            if (fenced.isNotBlank()) return fenced
        }

        val parser = SseMessageParser()
        val segments = parser.parse(message)
        var result = source
        segments.forEach { seg ->
            if (seg is SearchReplace) {
                val search = seg.search.trim()
                val replace = seg.replace
                if (search.isNotEmpty() && result.contains(search)) {
                    result = result.replaceFirst(search, replace)
                }
            }
        }
        return result
    }

    private fun extractTripleBacktickCode(message: String): String? {
        val fence = "```"
        val start = message.indexOf(fence)
        if (start == -1) return null
        // Skip optional language id on the opening fence
        var contentStart = start + fence.length
        if (contentStart < message.length && message[contentStart] == '\n') {
            contentStart += 1
        } else {
            val nl = message.indexOf('\n', contentStart)
            if (nl != -1) contentStart = nl + 1
        }
        val end = message.indexOf(fence, contentStart)
        if (end == -1) return null
        return message.substring(contentStart, end).trim()
    }
}

private fun buildEditDiffHistory(project: Project?): String {
    if (project == null) return ""

    val sb = StringBuilder()

    // Unstaged/staged current changes first
    try {
        GitUtil.getCurrentChanges(project)?.let { diff ->
            if (diff.isNotBlank()) {
                sb.append(diff.trim()).append('\n')
            }
        }
    } catch (_: Exception) {
        // ignore
    }

    val combined = sb.toString().trim()
    return service<EncodingManager>().truncateText(combined, 2048, true)
}
