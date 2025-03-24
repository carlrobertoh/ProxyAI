package ee.carlrobert.codegpt.toolwindow.chat;

import static java.util.Objects.requireNonNull;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComponentContainer;
import com.intellij.openapi.wm.RegisterToolWindowTask;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.Icons;
import ee.carlrobert.codegpt.completions.ConversationType;
import ee.carlrobert.codegpt.conversations.Conversation;
import ee.carlrobert.codegpt.conversations.ConversationService;
import ee.carlrobert.codegpt.conversations.ConversationsState;
import ee.carlrobert.codegpt.conversations.message.Message;
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.PROJECT)
public final class ChatToolWindowContentManager {

  private final Project project;

  public ChatToolWindowContentManager(Project project) {
    this.project = project;
  }

  public void sendMessageInNewTab(Message message, ConversationType conversationType) {
    createNewTabPanel().sendMessage(message, conversationType);
  }

  public void sendMessage(Message message) {
    sendMessage(message, ConversationType.DEFAULT);
  }

  public void sendMessage(Message message, ConversationType conversationType) {
    getToolWindow().show();

    var startInNewWindow = ApplicationManager.getApplication().getService(PromptsSettings.class)
        .getState()
        .getChatActions()
        .getStartInNewWindow();
    if (startInNewWindow || ConversationsState.getCurrentConversation() == null) {
      createNewTabPanel().sendMessage(message, conversationType);
      return;
    }

    tryFindChatTabbedPane()
        .map(tabbedPane -> tabbedPane.tryFindActiveTabPanel().orElseGet(this::createNewTabPanel))
        .orElseGet(this::createNewTabPanel)
        .sendMessage(message, conversationType);
  }

  public Optional<ChatToolWindowTabPanel> tryFindActiveChatTabPanel() {
    return tryFindChatTabbedPane().flatMap(ChatToolWindowTabbedPane::tryFindActiveTabPanel);
  }

  public void displayConversation(@NotNull Conversation conversation) {
    displayChatTab();
    tryFindChatTabbedPane()
        .ifPresent(tabbedPane -> tabbedPane.tryFindTabTitle(conversation.getId())
            .ifPresentOrElse(
                title -> tabbedPane.setSelectedIndex(tabbedPane.indexOfTab(title)),
                () -> tabbedPane.addNewTab(new ChatToolWindowTabPanel(project, conversation))));
  }

  public ChatToolWindowTabPanel createNewTabPanel() {
    displayChatTab();
    return tryFindChatTabbedPane()
        .map(item -> {
          var panel = new ChatToolWindowTabPanel(
              project,
              ConversationService.getInstance().startConversation());
          item.addNewTab(panel);
          return panel;
        })
        .orElseThrow();
  }

  public void displayChatTab() {
    var toolWindow = getToolWindow();
    toolWindow.show();

    var contentManager = toolWindow.getContentManager();
    tryFindFirstChatTabContent().ifPresentOrElse(
        contentManager::setSelectedContent,
        () -> contentManager.setSelectedContent(requireNonNull(contentManager.getContent(0)))
    );
  }

  public Optional<ChatToolWindowTabbedPane> tryFindChatTabbedPane() {
    var chatTabContent = tryFindFirstChatTabContent();
    if (chatTabContent.isPresent()) {
      var chatToolWindowPanel = (ChatToolWindowPanel) chatTabContent.get().getComponent();
      return Optional.of(chatToolWindowPanel.getChatTabbedPane());
    }
    return Optional.empty();
  }

  public Optional<ChatToolWindowPanel> tryFindChatToolWindowPanel() {
    return tryFindFirstChatTabContent()
        .map(ComponentContainer::getComponent)
        .filter(component -> component instanceof ChatToolWindowPanel)
        .map(component -> (ChatToolWindowPanel) component);
  }

  public void resetAll() {
    tryFindChatTabbedPane().ifPresent(tabbedPane -> {
      tabbedPane.clearAll();
      tabbedPane.addNewTab(new ChatToolWindowTabPanel(
          project,
          ConversationService.getInstance().startConversation()));
    });
  }

  public @NotNull ToolWindow getToolWindow() {
    var toolWindowManager = ToolWindowManager.getInstance(project);
    var toolWindow = toolWindowManager.getToolWindow("ProxyAI");
    // https://intellij-support.jetbrains.com/hc/en-us/community/posts/11533368171026/comments/11538403084562
    return Objects.requireNonNullElseGet(toolWindow, () -> toolWindowManager
        .registerToolWindow(RegisterToolWindowTask.closable(
            "ProxyAI",
            () -> CodeGPTBundle.get("project.label"),
            Icons.DefaultSmall,
            ToolWindowAnchor.RIGHT)));
  }

  private Optional<Content> tryFindFirstChatTabContent() {
    return Arrays.stream(getToolWindow().getContentManager().getContents())
        .filter(content -> "Chat".equals(content.getTabName()))
        .findFirst();
  }
}
