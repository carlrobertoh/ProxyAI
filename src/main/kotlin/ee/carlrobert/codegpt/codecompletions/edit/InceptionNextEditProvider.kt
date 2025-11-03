package ee.carlrobert.codegpt.codecompletions.edit

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.completions.CompletionClientProvider
import ee.carlrobert.codegpt.completions.CompletionRequestFactory.Companion.DEFAULT_LINES_AFTER
import ee.carlrobert.codegpt.completions.CompletionRequestFactory.Companion.DEFAULT_LINES_BEFORE
import ee.carlrobert.codegpt.completions.CompletionRequestFactory.Companion.MAX_EDITABLE_REGION_LINES
import ee.carlrobert.codegpt.completions.NextEditParameters
import ee.carlrobert.codegpt.completions.factory.InceptionRequestFactory
import ee.carlrobert.codegpt.predictions.NextEditSuggestionNavigator
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.service.ModelSelectionService
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.inception.InceptionSettings
import ee.carlrobert.codegpt.util.GitUtil
import ee.carlrobert.codegpt.util.MarkdownUtil
import ee.carlrobert.service.NextEditResponse
import java.util.*

class InceptionNextEditProvider : NextEditProvider {

    private val logger = thisLogger()

    override fun request(
        editor: Editor,
        fileContent: String,
        caretOffset: Int,
        addToQueue: Boolean
    ) {
        if (service<ModelSelectionService>().getServiceForFeature(FeatureType.NEXT_EDIT) != ServiceType.INCEPTION
            || !service<InceptionSettings>().state.nextEditsEnabled
        ) {
            return
        }

        try {
            runCatching { fetchNextEdit(editor, fileContent, caretOffset) }
                .onSuccess { response ->
                    if (response != null) {
                        if (addToQueue) {
                            CodeGPTKeys.REMAINING_NEXT_EDITS.set(editor, response)
                        } else {
                            runInEdt {
                                if (editor.document.text == response.oldRevision) {
                                    NextEditSuggestionNavigator.display(editor, response)
                                }
                            }
                        }
                    }
                }
                .onFailure { ex ->
                    logger.error("Something went wrong while retrieving next edit completion", ex)
                }
        } finally {
            editor.project?.let { CompletionProgressNotifier.update(it, false) }
        }
    }

    private fun fetchNextEdit(
        editor: Editor,
        fileContent: String,
        caretOffset: Int
    ): NextEditResponse? {
        val vf = FileDocumentManager.getInstance().getFile(editor.document)
        val request = InceptionRequestFactory().createNextEditRequest(
            NextEditParameters(
                project = editor.project,
                fileName = vf?.name ?: "unknown",
                filePath = vf?.path,
                fileContent = fileContent,
                caretOffset = caretOffset,
                gitDiff = editor.project?.let { GitUtil.getCurrentChanges(it) } ?: "",
                contextTokens = DEFAULT_CONTEXT_TOKENS
            )
        )

        val fullWithMarkers = NextEditRequestProcessor.buildFileWithMarkers(
            fileContent,
            caretOffset,
            10,
            25,
            50
        )
        val response = CompletionClientProvider.getInceptionClient().getNextEditCompletion(request)
        val text = response.choices?.firstOrNull()?.message?.content ?: return null
        val editedRegion = extractNextRevision(text)
        val next =
            NextEditRequestProcessor.replaceEditableRegionStrict(fullWithMarkers, editedRegion)
        return NextEditResponse.newBuilder()
            .setId(UUID.randomUUID().toString())
            .setOldRevision(fileContent)
            .setNextRevision(next)
            .build()
    }

    private fun extractNextRevision(message: String): String {
        return extractTripleBacktickCode(message)?.let { fenced ->
            if (fenced.isNotBlank()) {
                return fenced
            }
            return ""
        } ?: ""
    }

    private fun extractTripleBacktickCode(message: String): String? {
        val fence = "```"
        val start = message.indexOf(fence)
        if (start == -1) return null

        var contentStart = start + fence.length
        if (contentStart < message.length && message[contentStart] == '\n') {
            contentStart += 1
        } else {
            val nl = message.indexOf('\n', contentStart)
            if (nl != -1) contentStart = nl + 1
        }

        val end = message.lastIndexOf(fence)
        if (end == -1 || end <= start) return null

        return message.substring(contentStart, end).trim()
    }
}

private const val DEFAULT_CONTEXT_TOKENS = 4096
