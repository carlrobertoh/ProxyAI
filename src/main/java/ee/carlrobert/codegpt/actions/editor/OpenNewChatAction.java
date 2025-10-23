package ee.carlrobert.codegpt.actions.editor;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import ee.carlrobert.codegpt.Icons;
import ee.carlrobert.codegpt.conversations.ConversationsState;
import ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowContentManager;
import org.jetbrains.annotations.NotNull;

public class OpenNewChatAction extends AnAction {

  public OpenNewChatAction() {
    super(Icons.OpenNewTab);
  }

  @Override
  public void update(@NotNull AnActionEvent event) {
    event.getPresentation().setEnabled(event.getProject() != null);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    var project = event.getProject();
    if (project != null) {
      ConversationsState.getInstance(project).setCurrentConversation(null);
      var tabPanel =
          project.getService(ChatToolWindowContentManager.class).createNewTabPanel();
      if (tabPanel != null) {
        tabPanel.displayLandingView();
      }
    }
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }
}
