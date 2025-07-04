package ee.carlrobert.codegpt.actions.toolwindow;

import com.intellij.openapi.ui.InputValidator;
import com.intellij.openapi.ui.Messages;
import ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowTabbedPane;

public class RenameSessionAction {

  private static final int MAX_NAME_LENGTH = 50;

  public static void renameSession(ChatToolWindowTabbedPane tabbedPane, int tabIndex) {
    if (tabIndex <= 0) {
      return;
    }

    String currentTitle = tabbedPane.getTitleAt(tabIndex);
    String newName = Messages.showInputDialog(
        "Enter new title:",
        "Rename Title",
        Messages.getQuestionIcon(),
        currentTitle,
        new SessionNameValidator());

    if (newName != null && !newName.equals(currentTitle)) {
      tabbedPane.renameTab(tabIndex, newName);
    }
  }

  public static class SessionNameValidator implements InputValidator {

    @Override
    public boolean checkInput(String inputString) {
      if (inputString == null || inputString.trim().isEmpty()) {
        return false;
      }
      if (inputString.length() > MAX_NAME_LENGTH) {
        return false;
      }
      return !inputString.contains("\n") && !inputString.contains("\t");
    }

    @Override
    public boolean canClose(String inputString) {
      return checkInput(inputString);
    }
  }
}