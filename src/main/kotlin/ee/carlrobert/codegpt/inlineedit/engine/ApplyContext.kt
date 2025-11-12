package ee.carlrobert.codegpt.inlineedit.engine

import com.intellij.openapi.editor.ex.EditorEx
import ee.carlrobert.codegpt.inlineedit.InlineEditInlay
import ee.carlrobert.codegpt.inlineedit.InlineEditSubmissionHandler

data class ApplyContext(
    val editor: EditorEx,
    val inlay: InlineEditInlay,
    val submissionHandler: InlineEditSubmissionHandler,
    val promptText: String,
    val lastAssistantResponse: String
)

