package ee.carlrobert.codegpt.toolwindow.chat.ui;

import static com.intellij.util.ui.JBUI.Panels.simplePanel;

import com.intellij.icons.AllIcons.General;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import ee.carlrobert.codegpt.CodeGPTBundle;
import java.awt.BorderLayout;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.event.ItemEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;

public class ImageAccordion extends JPanel {

  public ImageAccordion(String fileName, byte[] imageData) {
    super(new BorderLayout());
    setOpaque(false);

    var contentPanel = createContentPanel(fileName, imageData);
    add(createToggleButton(contentPanel), BorderLayout.NORTH);
    add(contentPanel, BorderLayout.CENTER);
  }

  private JPanel createContentPanel(String fileName, byte[] imageData) {
    var panel = new JPanel();
    panel.setOpaque(false);
    panel.setVisible(true);
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setBorder(JBUI.Borders.empty(4, 0));
    try {
      ByteArrayInputStream inputStream = new ByteArrayInputStream(imageData);
      BufferedImage originalImage = ImageIO.read(inputStream);
      int maxHeight = 80;
      BufferedImage resizedImage = resizeImage(originalImage, maxHeight);
      panel.add(simplePanel()
          .andTransparent()
          .addToTop(
              new JBLabel("<html><small><strong>%s</strong></small></html>".formatted(fileName))
                  .withBorder(JBUI.Borders.emptyBottom(4)))
          .addToLeft(new JBLabel(new ImageIcon(resizedImage))), BorderLayout.LINE_START);
    } catch (IOException e) {
      panel.add(new JBLabel("ERROR: Something went wrong while reading the image"));
      throw new RuntimeException(e);
    }
    return panel;
  }

  private JToggleButton createToggleButton(JPanel contentPane) {
    var accordionToggle = new JToggleButton(
        CodeGPTBundle.get("imageAccordion.title"),
        General.ArrowDown);
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

  private BufferedImage resizeImage(BufferedImage originalImage, int maxHeight) {
    double aspectRatio = (double) originalImage.getWidth() / originalImage.getHeight();
    int newWidth = (int) (maxHeight * aspectRatio);
    Image resizedImage = originalImage.getScaledInstance(newWidth, maxHeight, Image.SCALE_SMOOTH);
    BufferedImage bufferedResizedImage = new BufferedImage(newWidth, maxHeight,
        BufferedImage.TYPE_INT_RGB);
    Graphics2D g2d = bufferedResizedImage.createGraphics();
    g2d.drawImage(resizedImage, 0, 0, null);
    g2d.dispose();
    return bufferedResizedImage;
  }
}
