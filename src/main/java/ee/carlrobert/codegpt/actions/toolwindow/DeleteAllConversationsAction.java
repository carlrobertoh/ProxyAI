package ee.carlrobert.codegpt.actions.toolwindow;

import static ee.carlrobert.codegpt.Icons.Default;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;
import ee.carlrobert.codegpt.actions.ActionType;
import ee.carlrobert.codegpt.actions.editor.EditorActionsUtil;
import ee.carlrobert.codegpt.conversations.ConversationService;
import ee.carlrobert.codegpt.telemetry.TelemetryAction;
import ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowContentManager;
import org.jetbrains.annotations.NotNull;

public class DeleteAllConversationsAction extends AnAction {

  private final Runnable onRefresh;

  public DeleteAllConversationsAction(Runnable onRefresh) {
    super("Delete All", "Delete all conversations", AllIcons.Actions.GC);
    this.onRefresh = onRefresh;
    EditorActionsUtil.registerAction(this);
  }

  @Override
  public void update(@NotNull AnActionEvent event) {
    var project = event.getProject();
    if (project != null) {
      var sortedConversations = ConversationService.getInstance(project).getSortedConversations();
      event.getPresentation().setEnabled(!sortedConversations.isEmpty());
    }
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    int answer = Messages.showYesNoDialog(
        "Are you sure you want to delete all conversations?",
        "Clear History",
        Default);
    if (answer == Messages.YES) {
      var project = event.getProject();
      if (project != null) {
        try {
          ConversationService.getInstance(project).clearAll();
          project.getService(ChatToolWindowContentManager.class).resetAll();
        } finally {
          TelemetryAction.IDE_ACTION.createActionMessage()
              .property("action", ActionType.DELETE_ALL_CONVERSATIONS.name())
              .send();
        }
      }
      this.onRefresh.run();
    }
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }
}
