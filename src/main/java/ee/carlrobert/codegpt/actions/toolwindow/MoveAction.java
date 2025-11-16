package ee.carlrobert.codegpt.actions.toolwindow;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import ee.carlrobert.codegpt.conversations.Conversation;
import ee.carlrobert.codegpt.conversations.ConversationsState;
import java.util.Optional;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

public abstract class MoveAction extends AnAction {

  private final Runnable onRefresh;

  protected abstract Optional<Conversation> getConversation(@NotNull Project project);

  protected MoveAction(String text, String description, Icon icon, Runnable onRefresh) {
    super(text, description, icon);
    this.onRefresh = onRefresh;
  }

  @Override
  public void update(@NotNull AnActionEvent event) {
    Project project = event.getProject();
    if (project == null) {
      event.getPresentation().setEnabled(false);
      return;
    }
    event.getPresentation().setEnabled(ConversationsState.getInstance(project).getCurrentConversation() != null);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    var project = event.getProject();
    if (project != null) {
      getConversation(project)
          .ifPresent(conversation -> {
            ConversationsState.getInstance(project).setCurrentConversation(conversation);
            onRefresh.run();
          });
    }
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }
}
