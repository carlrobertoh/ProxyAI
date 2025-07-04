package ee.carlrobert.codegpt.toolwindow.conversations;

import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import ee.carlrobert.codegpt.actions.toolwindow.DeleteConversationAction;
import ee.carlrobert.codegpt.conversations.Conversation;
import ee.carlrobert.codegpt.conversations.ConversationsState;
import ee.carlrobert.codegpt.toolwindow.chat.ChatToolWindowContentManager;
import ee.carlrobert.codegpt.ui.IconActionButton;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.format.DateTimeFormatter;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;

class ConversationPanel extends JPanel {

  ConversationPanel(
      @NotNull Project project,
      @NotNull Conversation conversation,
      @NotNull Runnable onDelete) {
    super(new BorderLayout());
    var toolWindowContentManager = project.getService(ChatToolWindowContentManager.class);
    init(toolWindowContentManager, conversation, onDelete);
  }

  private void init(
      ChatToolWindowContentManager toolWindowContentManager,
      Conversation conversation,
      Runnable onDelete) {
    setBackground(JBColor.background());
    addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        toolWindowContentManager.displayConversation(conversation);
      }
    });
    addStyles(isSelected(conversation));
    addTextPanel(conversation, onDelete);
    setCursor(new Cursor(Cursor.HAND_CURSOR));
  }

  private boolean isSelected(Conversation conversation) {
    var currentConversation = ConversationsState.getCurrentConversation();
    return currentConversation != null && currentConversation.getId().equals(conversation.getId());
  }

  private void addStyles(boolean isSelected) {
    var border = isSelected
        ? JBUI.Borders.customLine(JBUI.CurrentTheme.ActionButton.focusedBorder(), 2, 2, 2, 2)
        : JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 0);
    setBorder(JBUI.Borders.compound(border, JBUI.Borders.empty(8)));
    setLayout(new GridBagLayout());
    setCursor(new Cursor(Cursor.HAND_CURSOR));
  }

  private void addTextPanel(Conversation conversation, Runnable onDelete) {
    var constraints = new GridBagConstraints();
    constraints.gridx = 1;
    constraints.weightx = 1.0;
    constraints.fill = GridBagConstraints.HORIZONTAL;
    add(createTextPanel(conversation, onDelete), constraints);
  }

  private JPanel createTextPanel(Conversation conversation, Runnable onDelete) {
    var headerPanel = new JPanel(new GridBagLayout());
    headerPanel.setBorder(JBUI.Borders.emptyBottom(12));

    var gbc = new GridBagConstraints();
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 1.0;
    gbc.gridx = 0;

    headerPanel.add(new JBLabel(getConversationDisplayTitle(conversation))
        .withFont(JBFont.label().asBold()), gbc);

    gbc.gridx = 1;
    gbc.weightx = 0;
    headerPanel.add(new IconActionButton(new DeleteConversationAction(onDelete), "DELETE"), gbc);

    var bottomPanel = new JPanel(new BorderLayout());
    bottomPanel.add(new JLabel(conversation.getUpdatedOn()
        .format(DateTimeFormatter.ofPattern("M/d/yyyy, h:mm:ss a"))), BorderLayout.WEST);

    var textPanel = new JPanel(new BorderLayout());
    textPanel.add(headerPanel, BorderLayout.NORTH);
    textPanel.add(bottomPanel, BorderLayout.SOUTH);
    return textPanel;
  }

  private String getConversationDisplayTitle(Conversation conversation) {
    String title = conversation.getTitle();
    if (title != null && !title.trim().isEmpty()) {
      return title;
    }
    return getFirstPrompt(conversation);
  }

  private String getFirstPrompt(Conversation conversation) {
    var messages = conversation.getMessages();
    if (messages.isEmpty()) {
      return "";
    }
    return messages.get(0).getPrompt();
  }
}
