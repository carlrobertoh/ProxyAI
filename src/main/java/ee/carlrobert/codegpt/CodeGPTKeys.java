package ee.carlrobert.codegpt;

import com.intellij.openapi.util.Key;
import ee.carlrobert.codegpt.predictions.CodeSuggestionDiffViewer;
import ee.carlrobert.codegpt.toolwindow.chat.editor.ToolWindowEditorFileDetails;
import ee.carlrobert.llm.client.codegpt.CodeGPTUserDetails;
import ee.carlrobert.service.NextEditResponse;
import ee.carlrobert.service.PartialCodeCompletionResponse;

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
  public static final Key<PartialCodeCompletionResponse> REMAINING_CODE_COMPLETION =
      Key.create("codegpt.remainingCodeCompletion");
  public static final Key<NextEditResponse> REMAINING_PREDICTION_RESPONSE =
      Key.create("codegpt.remainingPredictionResponse");
  public static final Key<ToolWindowEditorFileDetails> TOOLWINDOW_EDITOR_FILE_DETAILS =
      Key.create("proxyai.toolwindowEditorFileDetails");
}
