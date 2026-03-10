package ee.carlrobert.codegpt.toolwindow.chat.ui;

import com.intellij.openapi.roots.ui.componentsList.components.ScrollablePanel;
import com.intellij.openapi.roots.ui.componentsList.layout.VerticalStackLayout;
import ee.carlrobert.codegpt.toolwindow.ui.ResponseMessagePanel;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

public class ChatToolWindowScrollablePanel extends ScrollablePanel {

  private final Map<UUID, JPanel> visibleMessagePanels = new HashMap<>();
  private boolean landingViewVisible;

  public ChatToolWindowScrollablePanel() {
    super(new VerticalStackLayout());
  }

  public void displayLandingView(JComponent landingView) {
    clearAll();
    add(landingView);
    landingViewVisible = true;
  }

  public ResponseMessagePanel getResponseMessagePanel(UUID messageId) {
    return (ResponseMessagePanel) Arrays.stream(visibleMessagePanels.get(messageId).getComponents())
        .filter(ResponseMessagePanel.class::isInstance)
        .findFirst().orElseThrow();
  }

  public JPanel addMessage(UUID messageId) {
    if (landingViewVisible) {
      clearAll();
    }
    var messageWrapper = new JPanel();
    messageWrapper.setLayout(new BoxLayout(messageWrapper, BoxLayout.PAGE_AXIS));
    add(messageWrapper);
    visibleMessagePanels.put(messageId, messageWrapper);
    return messageWrapper;
  }

  public void removeMessage(UUID messageId) {
    remove(visibleMessagePanels.get(messageId));
    update();
    visibleMessagePanels.remove(messageId);
  }

  public void clearLandingViewIfVisible() {
    if (landingViewVisible) {
      clearAll();
    }
  }

  public void clearAll() {
    visibleMessagePanels.clear();
    landingViewVisible = false;
    removeAll();
    update();
  }

  public void scrollToBottom() {
    scrollRectToVisible(new Rectangle(0, getHeight(), 1, 1));
  }

  public void update() {
    repaint();
    revalidate();
  }

  public JPanel getLastComponent() {
    var comp = getComponents()[getComponentCount() - 1];
    if (comp instanceof JPanel panel) {
      return panel;
    }
    return null;
  }
}
