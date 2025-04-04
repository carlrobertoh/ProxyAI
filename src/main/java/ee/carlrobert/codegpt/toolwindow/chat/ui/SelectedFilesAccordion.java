package ee.carlrobert.codegpt.toolwindow.chat.ui;

import static java.lang.String.format;

import com.intellij.icons.AllIcons.General;
import com.intellij.ui.components.ActionLink;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.event.ItemEvent;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import org.jetbrains.annotations.NotNull;

public class SelectedFilesAccordion extends JPanel {

  public SelectedFilesAccordion(@NotNull List<ActionLink> links) {
    super(new BorderLayout());
    setOpaque(false);

    var contentPanel = createContentPanel(links);
    add(createToggleButton(contentPanel, links.size()), BorderLayout.NORTH);
    add(contentPanel, BorderLayout.CENTER);
  }

  private JPanel createContentPanel(List<ActionLink> links) {
    var panel = new JPanel();
    panel.setOpaque(false);
    panel.setVisible(true);
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setBorder(JBUI.Borders.empty(4, 0));
    links.forEach(link -> {
      panel.add(link);
      panel.add(Box.createVerticalStrut(4));
    });
    return panel;
  }

  private JToggleButton createToggleButton(JPanel contentPane, int fileCount) {
    var accordionToggle = new JToggleButton(
        format("Referenced files (+%d)", fileCount), General.ArrowDown);
    accordionToggle.setFocusPainted(false);
    accordionToggle.setContentAreaFilled(false);
    accordionToggle.setBackground(getBackground());
    accordionToggle.setSelectedIcon(General.ArrowUp);
    accordionToggle.setBorder(null);
    accordionToggle.setSelected(true);
    accordionToggle.setHorizontalAlignment(SwingConstants.LEFT);
    accordionToggle.setHorizontalTextPosition(SwingConstants.RIGHT);
    accordionToggle.setIconTextGap(4);
    accordionToggle.addItemListener(e ->
        contentPane.setVisible(e.getStateChange() == ItemEvent.SELECTED));
    return accordionToggle;
  }
}