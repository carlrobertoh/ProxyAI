package ee.carlrobert.codegpt.toolwindow.chat.editor.actions;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.actions.ActionType;
import ee.carlrobert.codegpt.actions.TrackableAction;
import ee.carlrobert.codegpt.completions.ChatCompletionParameters;
import ee.carlrobert.codegpt.completions.CompletionClientProvider;
import ee.carlrobert.codegpt.conversations.message.Message;
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings;
import ee.carlrobert.codegpt.util.FeedbackUtil;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DislikeAction extends TrackableAction {
  private final static Logger log = Logger.getLogger(DislikeAction.class.getName());
  private final @NotNull Editor toolwindowEditor;

  public DislikeAction(@NotNull Editor toolwindowEditor) {
    super(
        CodeGPTBundle.get("shared.dislikeResponseCode"),
        CodeGPTBundle.get("shared.dislikeResponseDescription"),
        AllIcons.Ide.Like,
        ActionType.DISLIKE_RESPONSE);
    this.toolwindowEditor = toolwindowEditor;
  }
  @Override
  public void handleAction(@NotNull AnActionEvent e) {

  }

  public static void dislikeResponse(ChatCompletionParameters params){
    FeedbackUtil.dislikeResponse(params);
  }
}
