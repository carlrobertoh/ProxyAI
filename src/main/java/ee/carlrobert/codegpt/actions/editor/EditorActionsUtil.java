package ee.carlrobert.codegpt.actions.editor;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import ee.carlrobert.codegpt.completions.CompletionRequestUtil;
import ee.carlrobert.codegpt.conversations.message.Message;
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings;
import ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowContentManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.text.CaseUtils;

public class EditorActionsUtil {

  public static final Map<String, String> DEFAULT_ACTIONS = new LinkedHashMap<>(Map.of(
      "Find Bugs", "Find bugs and output code with bugs "
          + "fixed in the following code: {{selectedCode}}",
      "Write Tests", "Write Tests for the selected code {{selectedCode}}",
      "Explain", "Explain the selected code {{selectedCode}}",
      "Refactor", "Refactor the selected code {{selectedCode}}",
      "Optimize", "Optimize the selected code {{selectedCode}}"));

  public static void refreshActions() {
    AnAction actionGroup =
        ActionManager.getInstance().getAction("CodeGPT.MyEditorActionsGroup");
    if (actionGroup instanceof DefaultActionGroup group) {
      group.removeAll();
      ApplicationManager.getApplication().getService(PromptsSettings.class)
          .getState()
          .getChatActions()
          .getPrompts()
          .forEach((promptDetails) -> {
            // using label as action description to prevent com.intellij.diagnostic.PluginException
            // https://github.com/carlrobertoh/CodeGPT/issues/95
            var action = new BaseEditorAction(promptDetails.getName(), promptDetails.getName()) {
              @Override
              protected void actionPerformed(Project project, Editor editor, String selectedText) {
                var toolWindowContentManager =
                    project.getService(ChatToolWindowContentManager.class);
                toolWindowContentManager.getToolWindow().show();

                var prompt =
                    promptDetails.getInstructions() == null ? "" : promptDetails.getInstructions();
                var formattedCode = CompletionRequestUtil.formatCode(
                    selectedText,
                    editor.getVirtualFile().getPath());
                toolWindowContentManager.sendMessage(
                    new Message(prompt.replace("{SELECTION}", formattedCode)));
              }
            };
            group.add(action);
          });
    }
  }

  public static void registerAction(AnAction action) {
    ActionManager actionManager = ActionManager.getInstance();
    var actionId = convertToId(action.getTemplateText());
    if (actionManager.getAction(actionId) == null) {
      actionManager.registerAction(actionId, action, PluginId.getId("ee.carlrobert.chatgpt"));
    }
  }

  public static String convertToId(String label) {
    return "codegpt." + CaseUtils.toCamelCase(label, true);
  }
}
