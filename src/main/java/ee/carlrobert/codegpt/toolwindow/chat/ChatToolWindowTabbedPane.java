package ee.carlrobert.codegpt.toolwindow.chat;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.JBMenuItem;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.JBUI;
import ee.carlrobert.codegpt.actions.toolwindow.RenameSessionAction;
import ee.carlrobert.codegpt.conversations.ConversationService;
import ee.carlrobert.codegpt.conversations.ConversationsState;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

public class ChatToolWindowTabbedPane extends JBTabbedPane {

  private final Map<String, ChatToolWindowTabPanel> activeTabMapping = new TreeMap<>(
      (o1, o2) -> {
        String nums1 = o1.replaceAll("\\D", "");
        String nums2 = o2.replaceAll("\\D", "");

        if (!nums1.isEmpty() && !nums2.isEmpty()) {
          int n1 = Integer.parseInt(nums1);
          int n2 = Integer.parseInt(nums2);
          return Integer.compare(n1, n2);
        }

        if (!nums1.isEmpty()) {
          return -1;
        }
        if (!nums2.isEmpty()) {
          return 1;
        }

        return o1.compareToIgnoreCase(o2);
      });
  private final Disposable parentDisposable;

  public ChatToolWindowTabbedPane(Disposable parentDisposable) {
    this.parentDisposable = parentDisposable;
    setTabComponentInsets(null);
    setComponentPopupMenu(new TabPopupMenu());
    addChangeListener(e -> refreshTabState());
  }

  public Map<String, ChatToolWindowTabPanel> getActiveTabMapping() {
    return activeTabMapping;
  }

  public void addNewTab(ChatToolWindowTabPanel toolWindowPanel) {
    var tabIndices = activeTabMapping.keySet().toArray(new String[0]);
    var nextIndex = 0;
    for (String title : tabIndices) {
      if (title.matches("Chat \\d+")) {
        String numberPart = title.replaceAll("\\D+", "");
        int tabNum = Integer.parseInt(numberPart);
        if ((tabNum - 1) == nextIndex) {
          nextIndex++;
        } else {
          break;
        }
      }
    }

    String title = getTitle(toolWindowPanel, nextIndex);

    super.insertTab(title, null, toolWindowPanel.getContent(), null, nextIndex);
    activeTabMapping.put(title, toolWindowPanel);
    super.setSelectedIndex(nextIndex);

    if (nextIndex > 0) {
      setTabComponentAt(nextIndex, createCloseableTabButtonPanel(title));
      toolWindowPanel.requestFocusForTextArea();
    }

    Disposer.register(parentDisposable, toolWindowPanel);
  }

  private String getTitle(ChatToolWindowTabPanel toolWindowTabPanel, int nextIndex) {
    var conversation = toolWindowTabPanel.getConversation();
    String conversationTitle = (conversation != null) ? conversation.getTitle() : null;
    String customName = toolWindowTabPanel.getChatSession().getDisplayName();

    if (conversationTitle != null && !conversationTitle.trim().isEmpty()) {
      return ensureUniqueName(conversationTitle, null);
    }
    if (customName != null) {
      return ensureUniqueName(customName, null);
    }
    return "Chat " + (nextIndex + 1);
  }

  public Optional<String> tryFindTabTitle(UUID conversationId) {
    return activeTabMapping.entrySet().stream()
        .filter(entry -> {
          var panelConversation = entry.getValue().getConversation();
          return panelConversation != null && conversationId.equals(panelConversation.getId());
        })
        .findFirst()
        .map(Map.Entry::getKey);
  }

  public Optional<ChatToolWindowTabPanel> tryFindActiveTabPanel() {
    var selectedIndex = getSelectedIndex();
    if (selectedIndex == -1) {
      return Optional.empty();
    }

    return Optional.ofNullable(activeTabMapping.get(getTitleAt(selectedIndex)));
  }

  public void clearAll() {
    removeAll();
    activeTabMapping.clear();
  }

  public void renameTab(int tabIndex, String newName) {
    if (tabIndex < 0 || tabIndex >= getTabCount()) {
      return;
    }

    String oldTitle = getTitleAt(tabIndex);
    ChatToolWindowTabPanel panel = activeTabMapping.get(oldTitle);

    if (panel == null) {
      return;
    }

    String uniqueName = ensureUniqueName(newName, oldTitle);

    setTitleAt(tabIndex, uniqueName);

    if (tabIndex > 0) {
      setTabComponentAt(tabIndex, createCloseableTabButtonPanel(uniqueName));
    }

    activeTabMapping.remove(oldTitle);
    activeTabMapping.put(uniqueName, panel);

    var conversation = panel.getConversation();
    if (conversation != null) {
      conversation.setTitle(uniqueName);
    }

    panel.getChatSession().setName(uniqueName);
  }

  String ensureUniqueName(String desiredName, String currentTitle) {
    String baseName = desiredName.trim();
    String uniqueName = baseName;
    int counter = 2;

    while (activeTabMapping.containsKey(uniqueName) && !uniqueName.equals(currentTitle)) {
      uniqueName = baseName + " (" + counter + ")";
      counter++;
    }

    return uniqueName;
  }

  private void refreshTabState() {
    var selectedIndex = getSelectedIndex();
    if (selectedIndex == -1) {
      return;
    }

    var toolWindowPanel = activeTabMapping.get(getTitleAt(selectedIndex));
    if (toolWindowPanel != null) {
      var conversation = toolWindowPanel.getConversation();
      if (conversation != null) {
        ConversationsState.getInstance().setCurrentConversation(conversation);
      }
    }
  }

  public void resetCurrentlyActiveTabPanel(Project project) {
    tryFindActiveTabPanel().ifPresent(tabPanel -> {
      Disposer.dispose(tabPanel);
      activeTabMapping.remove(getTitleAt(getSelectedIndex()));
      removeTabAt(getSelectedIndex());
      addNewTab(new ChatToolWindowTabPanel(
          project,
          ConversationService.getInstance().startConversation(project)));
      repaint();
      revalidate();
    });
  }

  private JPanel createCloseableTabButtonPanel(String title) {
    var closeIcon = AllIcons.Actions.Close;
    var button = new JButton(closeIcon);
    button.addActionListener(new CloseActionListener(title));
    button.setPreferredSize(new Dimension(closeIcon.getIconWidth(), closeIcon.getIconHeight()));
    button.setBorder(BorderFactory.createEmptyBorder());
    button.setContentAreaFilled(false);
    button.setToolTipText("Close Chat");
    button.setRolloverIcon(AllIcons.Actions.CloseHovered);

    return JBUI.Panels.simplePanel(4, 0)
        .addToLeft(new JBLabel(title))
        .addToRight(button)
        .andTransparent();
  }

  class CloseActionListener implements ActionListener {

    private final String title;

    public CloseActionListener(String title) {
      this.title = title;
    }

    public void actionPerformed(ActionEvent evt) {
      var tabIndex = indexOfTab(title);
      if (tabIndex >= 0) {
        Disposer.dispose(activeTabMapping.get(title));
        removeTabAt(tabIndex);
        activeTabMapping.remove(title);
      }
    }
  }

  class TabPopupMenu extends JPopupMenu {

    private int selectedPopupTabIndex = -1;

    TabPopupMenu() {
      add(createPopupMenuItem("Rename Title", e -> {
        if (selectedPopupTabIndex > 0) {
          RenameSessionAction.renameSession(ChatToolWindowTabbedPane.this, selectedPopupTabIndex);
        }
      }));
      addSeparator();
      add(createPopupMenuItem("Close", e -> {
        if (selectedPopupTabIndex > 0) {
          activeTabMapping.remove(getTitleAt(selectedPopupTabIndex));
          removeTabAt(selectedPopupTabIndex);
        }
      }));
      add(createPopupMenuItem("Close Other Tabs", e -> {
        var selectedPopupTabTitle = getTitleAt(selectedPopupTabIndex);
        var tabPanel = activeTabMapping.get(selectedPopupTabTitle);

        clearAll();
        addNewTab(tabPanel);
      }));
    }

    @Override
    public void show(Component invoker, int x, int y) {
      selectedPopupTabIndex = ChatToolWindowTabbedPane.this.getUI()
          .tabForCoordinate(ChatToolWindowTabbedPane.this, x, y);
      if (selectedPopupTabIndex > 0) {
        super.show(invoker, x, y);
      }
    }

    private JBMenuItem createPopupMenuItem(String label, ActionListener listener) {
      var menuItem = new JBMenuItem(label);
      menuItem.addActionListener(listener);
      return menuItem;
    }
  }
}
