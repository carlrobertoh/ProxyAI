package ee.carlrobert.codegpt.toolwindow.chat.ui;

import static javax.swing.event.HyperlinkEvent.EventType.ACTIVATED;

import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.roots.ui.componentsList.components.ScrollablePanel;
import com.intellij.openapi.roots.ui.componentsList.layout.VerticalStackLayout;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import ee.carlrobert.codegpt.credentials.CredentialsStore;
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey;
import ee.carlrobert.codegpt.settings.GeneralSettings;
import ee.carlrobert.codegpt.settings.service.ServiceType;
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceConfigurable;
import ee.carlrobert.codegpt.toolwindow.ui.ResponseMessagePanel;
import ee.carlrobert.codegpt.ui.UIUtil;
import ee.carlrobert.codegpt.util.ApplicationUtil;
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

  public ChatToolWindowScrollablePanel() {
    super(new VerticalStackLayout());
  }

  public void displayLandingView(JComponent landingView) {
    clearAll();
    add(landingView);
    if (GeneralSettings.isSelected(ServiceType.CODEGPT)
        && !CredentialsStore.INSTANCE.isCredentialSet(CredentialKey.CodeGptApiKey.INSTANCE)) {

      var panel = new ResponseMessagePanel();
      panel.addContent(UIUtil.createTextPane("""
              <html>
              <p style="margin-top: 4px; margin-bottom: 4px;">
                It looks like you haven't configured your API key yet. Visit <a href="#OPEN_SETTINGS">ProxyAI settings</a> to do so.
              </p>
              <p style="margin-top: 4px; margin-bottom: 4px;">
                Don't have an account? <a href="https://tryproxy.io/signin">Sign up</a> to get the most out of ProxyAI.
              </p>
              </html>""",
          false,
          event -> {
            if (ACTIVATED.equals(event.getEventType())
                && "#OPEN_SETTINGS".equals(event.getDescription())) {
              ShowSettingsUtil.getInstance().showSettingsDialog(
                  ApplicationUtil.findCurrentProject(),
                  CodeGPTServiceConfigurable.class);
            } else {
              UIUtil.handleHyperlinkClicked(event);
            }
          }));
      panel.setBorder(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0));
      add(panel);
    }
  }

  public ResponseMessagePanel getResponseMessagePanel(UUID messageId) {
    return (ResponseMessagePanel) Arrays.stream(visibleMessagePanels.get(messageId).getComponents())
        .filter(ResponseMessagePanel.class::isInstance)
        .findFirst().orElseThrow();
  }

  public JPanel addMessage(UUID messageId) {
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

  public void clearAll() {
    visibleMessagePanels.clear();
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
}
