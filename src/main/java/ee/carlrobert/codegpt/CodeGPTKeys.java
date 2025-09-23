package ee.carlrobert.codegpt;

import com.intellij.openapi.util.Key;
import ee.carlrobert.codegpt.inlineedit.InlineEditSession;
import ee.carlrobert.codegpt.inlineedit.InlineEditInlayRenderer;
import ee.carlrobert.codegpt.predictions.CodeSuggestionDiffViewer;
import ee.carlrobert.codegpt.toolwindow.chat.editor.ToolWindowEditorFileDetails;
import ee.carlrobert.llm.client.codegpt.CodeGPTUserDetails;
import ee.carlrobert.service.NextEditResponse;
import ee.carlrobert.service.PartialCodeCompletionResponse;
import javax.swing.JComponent;

public class CodeGPTKeys {

  public static final Key<String> IMAGE_ATTACHMENT_FILE_PATH =
      Key.create("codegpt.imageAttachmentFilePath");
  public static final Key<CodeGPTUserDetails> CODEGPT_USER_DETAILS =
      Key.create("codegpt.userDetails");
  public static final Key<String> REMAINING_EDITOR_COMPLETION =
      Key.create("codegpt.editorCompletionLines");
  public static final Key<Boolean> COMPLETION_IN_PROGRESS =
      Key.create("codegpt.completionInProgress");
  public static final Key<Boolean> IS_PROMPT_TEXT_FIELD_DOCUMENT =
      Key.create("codegpt.isPromptTextFieldDocument");
  public static final Key<CodeSuggestionDiffViewer> EDITOR_PREDICTION_DIFF_VIEWER =
      Key.create("codegpt.editorPredictionDiffViewer");
  public static final Key<InlineEditSession> EDITOR_INLINE_EDIT_SESSION =
      Key.create("codegpt.editorInlineEditSession");
  public static final Key<InlineEditInlayRenderer> EDITOR_INLINE_EDIT_RENDERER =
      Key.create("codegpt.editorInlineEditRenderer");
  public static final Key<JComponent> EDITOR_INLINE_EDIT_COMPARE_LINK =
      Key.create("codegpt.editorInlineEditCompareLink");
  public static final Key<JComponent> EDITOR_INLINE_EDIT_ACCEPT_ALL_CHIP =
      Key.create("codegpt.editorInlineEditAcceptAllChip");
  public static final Key<JComponent> EDITOR_INLINE_EDIT_REJECT_ALL_CHIP =
      Key.create("codegpt.editorInlineEditRejectAllChip");
  public static final Key<PartialCodeCompletionResponse> REMAINING_CODE_COMPLETION =
      Key.create("codegpt.remainingCodeCompletion");
  public static final Key<NextEditResponse> REMAINING_PREDICTION_RESPONSE =
      Key.create("codegpt.remainingPredictionResponse");
  public static final Key<ToolWindowEditorFileDetails> TOOLWINDOW_EDITOR_FILE_DETAILS =
      Key.create("proxyai.toolwindowEditorFileDetails");
}
