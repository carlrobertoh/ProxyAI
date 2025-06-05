package ee.carlrobert.codegpt.toolwindow.chat.editor.actions;

import static com.intellij.openapi.application.ActionsKt.runUndoTransparentWriteAction;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileEditor.FileEditorManager;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.Icons;
import ee.carlrobert.codegpt.ui.OverlayUtil;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.util.Optional;
import javax.swing.AbstractAction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class InsertAtCaretAction extends AbstractAction {

  private final @NotNull Editor toolwindowEditor;
  private final @Nullable Point locationOnScreen;

  public InsertAtCaretAction(
      @NotNull EditorEx toolwindowEditor,
      @Nullable Point locationOnScreen) {
    super(
        CodeGPTBundle.get("toolwindow.chat.editor.action.insertAtCaret.title"),
        Icons.SendToTheLeft);
    this.toolwindowEditor = toolwindowEditor;
    this.locationOnScreen = locationOnScreen;
  }

  @Override
  public void actionPerformed(ActionEvent e) {
    Editor mainEditor = getSelectedTextEditor();
    if (mainEditor == null) {
      OverlayUtil.showWarningBalloon("Active editor not found", locationOnScreen);
      return;
    }

    insertTextAtCaret(mainEditor);
  }

  @Nullable
  private Editor getSelectedTextEditor() {
    return Optional.ofNullable(toolwindowEditor.getProject())
        .map(FileEditorManager::getInstance)
        .map(FileEditorManager::getSelectedTextEditor)
        .orElse(null);
  }

  private void insertTextAtCaret(Editor mainEditor) {
    runUndoTransparentWriteAction(() -> {
      mainEditor.getDocument().insertString(
          mainEditor.getCaretModel().getOffset(),
          toolwindowEditor.getDocument().getText());
      return null;
    });
  }
}
