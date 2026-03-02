package ee.carlrobert.codegpt.nextedit

import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import ee.carlrobert.codegpt.CodeGPTKeys
import ee.carlrobert.codegpt.codecompletions.CompletionProgressNotifier
import ee.carlrobert.codegpt.completions.NextEditParameters
import ee.carlrobert.codegpt.completions.factory.InceptionRequestFactory
import ee.carlrobert.codegpt.nextedit.executors.InceptionNextEditExecutor
import ee.carlrobert.codegpt.settings.service.FeatureType
import ee.carlrobert.codegpt.settings.models.ModelSettings
import ee.carlrobert.codegpt.settings.service.ServiceType
import ee.carlrobert.codegpt.settings.service.inception.InceptionSettings
import ee.carlrobert.codegpt.util.EditWindowFormatter
import ee.carlrobert.codegpt.util.GitUtil
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
        if (service<ModelSettings>().getServiceForFeature(FeatureType.NEXT_EDIT) != ServiceType.INCEPTION
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
                                    NextEditDiffViewer.displayNextEdit(editor, response)
                                }
                            }
                        }
                    }
                }
                .onFailure { ex ->
                    logger.error("Something went wrong while retrieving next edit completion", ex)
                }
        } finally {
            editor.project?.let { CompletionProgressNotifier.Companion.update(it, false) }
        }
    }

    private fun fetchNextEdit(
        editor: Editor,
        fileContent: String,
        caretOffset: Int
    ): NextEditResponse? {
        val vf = FileDocumentManager.getInstance().getFile(editor.document)
        val params = NextEditParameters(
            project = editor.project,
            fileName = vf?.name ?: "unknown",
            filePath = vf?.path,
            fileContent = fileContent,
            caretOffset = caretOffset,
            gitDiff = editor.project?.let { GitUtil.getCurrentChanges(it) } ?: "",
            contextTokens = DEFAULT_CONTEXT_TOKENS
        )
        val formatResult =
            EditWindowFormatter.formatWithIndices(fileContent, params.fileName, caretOffset)
        val request =
            InceptionRequestFactory().createNextEditRequest(params, formatResult)
        val response = InceptionNextEditExecutor.execute(request)
        val text = response.choices?.firstOrNull()?.message?.content ?: return null
        val prefix = fileContent.substring(0, formatResult.editStartIndex)
        val editedContent = NextEditRequestProcessor.extractCodeFromBackticks(text)
        val suffix = fileContent.substring(formatResult.editEndIndex)
        return NextEditResponse.newBuilder()
            .setId(UUID.randomUUID().toString())
            .setOldRevision(fileContent)
            .setNextRevision(prefix + editedContent + suffix)
            .build()
    }
}

const val DEFAULT_CONTEXT_TOKENS: Int = 2048
