package ee.carlrobert.codegpt.ui;

import static javax.swing.event.HyperlinkEvent.EventType.ACTIVATED;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.roots.ui.componentsList.components.ScrollablePanel;
import com.intellij.openapi.ui.panel.ComponentPanelBuilder;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UI;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.toolwindow.chat.ui.SmartScroller;
import ee.carlrobert.codegpt.util.PsiLinkNavigator;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;

public class UIUtil {

  private static final Logger LOG = Logger.getInstance(UIUtil.class);

  public static JTextPane createTextPane(String text) {
    return createTextPane(text, true);
  }

  public static JTextPane createTextPane(String text, boolean opaque) {
    return createTextPane(text, opaque, UIUtil::handleHyperlinkClicked);
  }

  public static JTextPane createTextPane(String text, boolean opaque, HyperlinkListener listener) {
    var textPane = new JTextPane();
    textPane.putClientProperty(JTextPane.HONOR_DISPLAY_PROPERTIES, true);
    var editorKit = new HTMLEditorKitBuilder().withWordWrapViewFactory().build();
    StyleSheet styleSheet = editorKit.getStyleSheet();
    styleSheet.addRule("code { white-space: pre-wrap; word-wrap: break-word; }");
    textPane.setEditorKit(editorKit);
    textPane.addHyperlinkListener(listener);
    textPane.setContentType("text/html");
    textPane.setEditable(false);
    textPane.setText(text);
    textPane.setOpaque(opaque);
    ((DefaultCaret) textPane.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
    return textPane;
  }

  public static JBTextArea createTextArea(String initialValue) {
    var textArea = new JBTextArea(initialValue);
    textArea.setRows(3);
    textArea.setBorder(JBUI.Borders.compound(
        JBUI.Borders.customLine(JBColor.border()),
        JBUI.Borders.empty(4)));
    textArea.setLineWrap(true);
    return textArea;
  }

  public static JButton createIconButton(Icon icon) {
    var button = new JButton(icon);
    button.setBorder(BorderFactory.createEmptyBorder());
    button.setContentAreaFilled(false);
    button.setPreferredSize(new Dimension(icon.getIconWidth(), icon.getIconHeight()));
    return button;
  }

  public static JScrollPane createScrollPaneWithSmartScroller(ScrollablePanel scrollablePanel) {
    var scrollPane = ScrollPaneFactory.createScrollPane(scrollablePanel, true);
    new SmartScroller(scrollPane);
    return scrollPane;
  }

  public static void setEqualLabelWidths(JPanel firstPanel, JPanel secondPanel) {
    var firstLabel = firstPanel.getComponents()[0];
    var secondLabel = secondPanel.getComponents()[0];
    if (firstLabel instanceof JLabel && secondLabel instanceof JLabel) {
      firstLabel.setPreferredSize(secondLabel.getPreferredSize());
    }
  }

  public static JPanel createPanel(JComponent component, String label, boolean resizeX) {
    return UI.PanelFactory.panel(component)
        .withLabel(label)
        .resizeX(resizeX)
        .createPanel();
  }

  public static void handleHyperlinkClicked(HyperlinkEvent event) {
    if (!ACTIVATED.equals(event.getEventType())) {
      return;
    }

    String desc = event.getDescription();
    if (desc != null && PsiLinkNavigator.isValidNavigationLink(desc)) {
      ApplicationManager.getApplication()
          .executeOnPooledThread(() -> PsiLinkNavigator.handle(desc));
      return;
    }

    var url = event.getURL();
    if (url != null) {
      try {
        BrowserUtil.browse(url.toURI());
      } catch (URISyntaxException e) {
        LOG.warn("Failed to browse URL: " + url, e);
      }
    }
  }

  public static void addShiftEnterInputMap(JTextArea textArea, AbstractAction onSubmit) {
    textArea.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break");
    textArea.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "text-submit");
    textArea.getActionMap().put("text-submit", onSubmit);
  }

  public static JPanel createRadioButtonsPanel(List<JBRadioButton> radioButtons) {
    var buttonGroup = new ButtonGroup();
    var radioPanel = new JPanel();
    radioPanel.setLayout(new BoxLayout(radioPanel, BoxLayout.PAGE_AXIS));
    for (int i = 0; i < radioButtons.size(); i++) {
      JBRadioButton radioButton = radioButtons.get(i);
      buttonGroup.add(radioButton);
      radioPanel.add(radioButton);
      radioPanel.add(Box.createVerticalStrut(i == radioButtons.size() - 1 ? 8 : 4));
    }
    return withEmptyLeftBorder(radioPanel);
  }

  public static <T extends JComponent> T withEmptyLeftBorder(T component) {
    component.setBorder(JBUI.Borders.emptyLeft(16));
    return component;
  }

  public static JLabel createComment(String messageKey) {
    return createComment(messageKey, ComponentPanelBuilder.MAX_COMMENT_WIDTH);
  }

  public static JLabel createComment(String messageKey, int maxLineLength) {
    var comment = ComponentPanelBuilder.createCommentComponent(
        CodeGPTBundle.get(messageKey),
        true,
        maxLineLength);
    comment.setBorder(JBUI.Borders.empty(0, 4));
    return comment;
  }

  public static JPanel createForm(Map<String, RadioButtonWithLayout> layouts,
      String initialLayout) {
    JPanel finalPanel = new JPanel(new BorderLayout());
    finalPanel.add(createRadioButtonsPanel(layouts.values().stream().map(
            RadioButtonWithLayout::getRadioButton).toList()),
        BorderLayout.NORTH);
    finalPanel.add(createRadioButtonGroupLayouts(layouts, initialLayout), BorderLayout.CENTER);
    return finalPanel;
  }

  /**
   * Creates RadioButton group to toggle between different layouts.
   *
   * @param layouts       Map from layout name to RadioButton + Layout to be shown
   * @param initialLayout Key of {@code layouts} entry to be initially shown
   * @return Panel with the RadioButton group
   */
  public static JPanel createRadioButtonGroupLayouts(
      Map<String, RadioButtonWithLayout> layouts,
      String initialLayout) {
    CardLayout cardlayout = new CardLayout() {
      @Override
      public void show(Container parent, String name) {
        super.show(parent, name);
        // Set height to selected components height instead of consistent height
        Arrays.stream(parent.getComponents())
            .filter(component -> name.equals(component.getName()))
            .findFirst()
            .map(component -> (int) component.getPreferredSize().getHeight())
            .map(height -> new Dimension(parent.getPreferredSize().width, height))
            .ifPresent(parent::setPreferredSize);
      }
    };

    var formPanelCards = new JPanel(cardlayout);
    for (Entry<String, RadioButtonWithLayout> layout : layouts.entrySet()) {
      RadioButtonWithLayout value = layout.getValue();
      Component component = value.getComponent();
      String key = layout.getKey();
      component.setName(key);
      formPanelCards.add(component, key);
      value.getRadioButton().addActionListener(e -> cardlayout.show(formPanelCards, key));
    }

    cardlayout.show(formPanelCards, initialLayout);
    return formPanelCards;
  }

  public static class RadioButtonWithLayout {

    private final JBRadioButton radioButton;
    private final Component layout;

    public RadioButtonWithLayout(JBRadioButton radioButton, Component layout) {
      this.radioButton = radioButton;
      this.layout = layout;
    }

    public JBRadioButton getRadioButton() {
      return radioButton;
    }

    public Component getComponent() {
      return layout;
    }
  }
}
