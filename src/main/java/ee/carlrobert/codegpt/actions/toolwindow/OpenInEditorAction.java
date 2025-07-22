package ee.carlrobert.codegpt.actions.toolwindow;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.testFramework.LightVirtualFile;
import ee.carlrobert.codegpt.actions.ActionType;
import ee.carlrobert.codegpt.actions.editor.EditorActionsUtil;
import ee.carlrobert.codegpt.conversations.ConversationsState;
import ee.carlrobert.codegpt.telemetry.TelemetryAction;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

public class OpenInEditorAction extends AnAction {

  public OpenInEditorAction() {
    super("Open In Editor", "Open conversation in editor", AllIcons.Actions.SplitVertically);
    EditorActionsUtil.registerAction(this);
  }

  @Override
  public void update(@NotNull AnActionEvent event) {
    super.update(event);
    var currentConversation = ConversationsState.getCurrentConversation();
    var isEnabled = currentConversation != null && !currentConversation.getMessages().isEmpty();
    event.getPresentation().setEnabled(isEnabled);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    try {
      var project = e.getProject();
      var currentConversation = ConversationsState.getCurrentConversation();
      if (project != null && currentConversation != null) {
        var dateTimeStamp = currentConversation.getUpdatedOn()
            .format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        var fileName = format("proxyai_conversation_%s.md", dateTimeStamp);
        var fileContent = currentConversation
            .getMessages()
            .stream()
            .map(it -> format("### User:%n%s%n### CodeGPT:%n%s%n", it.getPrompt(),
                it.getResponse()))
            .collect(Collectors.joining());
        VirtualFile file = new LightVirtualFile(fileName, fileContent);
        FileEditorManager.getInstance(project).openFile(file, true);
        var toolWindow = requireNonNull(
            ToolWindowManager.getInstance(project).getToolWindow("ProxyAI"));
        toolWindow.hide();
      }
    } finally {
      TelemetryAction.IDE_ACTION.createActionMessage()
          .property("action", ActionType.OPEN_CONVERSATION_IN_EDITOR.name())
          .send();
    }
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }
}
