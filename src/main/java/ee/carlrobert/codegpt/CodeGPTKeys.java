package ee.carlrobert.codegpt;

import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.util.Key;
import ee.carlrobert.codegpt.predictions.CodeSuggestionDiffViewer;
import ee.carlrobert.llm.client.codegpt.CodeGPTUserDetails;

public class CodeGPTKeys {

  public static final Key<String> PREVIOUS_INLAY_TEXT =
      Key.create("codegpt.editor.inlay.prev-value");
  public static final Key<Inlay<EditorCustomElementRenderer>> SINGLE_LINE_INLAY =
      Key.create("codegpt.editor.inlay.single-line");
  public static final Key<Inlay<EditorCustomElementRenderer>> MULTI_LINE_INLAY =
      Key.create("codegpt.editor.inlay.multi-line");
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
}
