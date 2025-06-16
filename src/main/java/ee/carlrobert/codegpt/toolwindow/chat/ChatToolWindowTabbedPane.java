package ee.carlrobert.codegpt.toolwindow.chat;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.JBMenuItem;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.JBUI;
import ee.carlrobert.codegpt.conversations.ConversationService;
import ee.carlrobert.codegpt.conversations.ConversationsState;
import ee.carlrobert.codegpt.settings.GeneralSettings;
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
        int n1 = Integer.parseInt(o1.replaceAll("\\D", ""));
        int n2 = Integer.parseInt(o2.replaceAll("\\D", ""));
        return Integer.compare(n1, n2);
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
      int tabNum = Integer.parseInt(title.replaceAll("\\D+", ""));
      if ((tabNum - 1) == nextIndex) {
        nextIndex++;
      } else {
        break;
      }
    }

    var title = "Chat " + (nextIndex + 1);
    super.insertTab(title, null, toolWindowPanel.getContent(), null, nextIndex);
    activeTabMapping.put(title, toolWindowPanel);
    super.setSelectedIndex(nextIndex);

    if (nextIndex > 0) {
      setTabComponentAt(nextIndex, createCloseableTabButtonPanel(title));
      toolWindowPanel.requestFocusForTextArea();
    }

    Disposer.register(parentDisposable, toolWindowPanel);
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
          ConversationService.getInstance().startConversation()));
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
