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
import ee.carlrobert.codegpt.completions.CompletionRequestFactory;
import ee.carlrobert.codegpt.completions.factory.CustomOpenAIRequest;
import ee.carlrobert.codegpt.conversations.message.Message;
import ee.carlrobert.codegpt.settings.GeneralSettings;
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings;
import ee.carlrobert.codegpt.util.FeedbackUtil;
import ee.carlrobert.llm.completion.CompletionRequest;
import groovy.util.logging.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.h2.mvstore.cache.CacheLongKeyLIRS;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

@Slf4j
public class LikeAction extends TrackableAction {
  private final @NotNull Editor toolwindowEditor;

  public LikeAction(@NotNull Editor toolwindowEditor) {
    super(
        CodeGPTBundle.get("shared.likeResponseCode"),
        CodeGPTBundle.get("shared.likeResponseDescription"),
        AllIcons.Ide.Like,
        ActionType.LIKE_RESPONSE);
    this.toolwindowEditor = toolwindowEditor;
  }

  @Override
  public void handleAction(@NotNull AnActionEvent e) {
  }

  public static void likeResponse(ChatCompletionParameters params){
    FeedbackUtil.likeResponse(params);
  }
}
