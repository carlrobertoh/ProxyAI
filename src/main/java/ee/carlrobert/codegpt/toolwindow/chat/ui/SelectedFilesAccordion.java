package ee.carlrobert.codegpt.toolwindow.chat.ui;

import static java.lang.String.format;

import com.intellij.icons.AllIcons.General;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.ActionLink;
import com.intellij.util.ui.JBUI;
import java.awt.BorderLayout;
import java.awt.event.ItemEvent;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import org.jetbrains.annotations.NotNull;

public class SelectedFilesAccordion extends JPanel {

  public SelectedFilesAccordion(
      @NotNull Project project,
      @NotNull List<VirtualFile> referencedFiles) {
    super(new BorderLayout());
    setOpaque(false);

    var contentPanel = createContentPanel(project, referencedFiles);
    add(createToggleButton(contentPanel, referencedFiles.size()), BorderLayout.NORTH);
    add(contentPanel, BorderLayout.CENTER);
  }

  private JPanel createContentPanel(Project project, List<VirtualFile> referencedFiles) {
    var panel = new JPanel();
    panel.setOpaque(false);
    panel.setVisible(true);
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setBorder(JBUI.Borders.empty(4, 0));
    referencedFiles.stream()
        .map(virtualFile -> {
          var actionLink = new ActionLink(
              Paths.get(virtualFile.getPath()).getFileName().toString(),
              event -> {
                FileEditorManager.getInstance(project)
                    .openFile(Objects.requireNonNull(virtualFile), true);
              });
          actionLink.setIcon(virtualFile.getFileType().getIcon());
          return actionLink;
        })
        .forEach(link -> {
          panel.add(link);
          panel.add(Box.createVerticalStrut(4));
        });
    return panel;
  }

  private JToggleButton createToggleButton(JPanel contentPane, int fileCount) {
    var accordionToggle = new JToggleButton(
        format("Referenced files (+%d)", fileCount), General.ArrowUp);
    accordionToggle.setFocusPainted(false);
    accordionToggle.setContentAreaFilled(false);
    accordionToggle.setBackground(getBackground());
    accordionToggle.setSelectedIcon(General.ArrowDown);
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