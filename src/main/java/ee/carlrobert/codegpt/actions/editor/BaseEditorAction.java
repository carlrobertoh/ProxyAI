package ee.carlrobert.codegpt.actions.editor;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsActions;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class BaseEditorAction extends AnAction {

  BaseEditorAction(@Nullable Icon icon) {
    super(icon);
  }

  BaseEditorAction(
      @Nullable @NlsActions.ActionText String text,
      @Nullable @NlsActions.ActionDescription String description,
      @Nullable Icon icon) {
    super(text, description, icon);
    EditorActionsUtil.registerAction(this);
  }

  BaseEditorAction(
      @Nullable @NlsActions.ActionText String text,
      @Nullable @NlsActions.ActionDescription String description) {
    this(text, description, null);
  }

  protected abstract void actionPerformed(Project project, Editor editor, String selectedText);

  public void actionPerformed(@NotNull AnActionEvent event) {
    var project = event.getProject();
    var editor = event.getData(PlatformDataKeys.EDITOR);
    if (editor != null && project != null) {
      var selectedText = editor.getSelectionModel().getSelectedText();
      actionPerformed(project, editor, selectedText != null ? selectedText : "");
    }
  }

  public void update(AnActionEvent event) {
    Project project = event.getProject();
    Editor editor = event.getData(PlatformDataKeys.EDITOR);
    boolean menuAllowed = editor != null && project != null;
    event.getPresentation().setEnabled(menuAllowed);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.EDT;
  }
}
