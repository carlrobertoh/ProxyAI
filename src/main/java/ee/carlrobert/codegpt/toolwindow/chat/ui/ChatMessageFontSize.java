package ee.carlrobert.codegpt.toolwindow.chat.ui;

import com.intellij.util.ui.JBUI;
import ee.carlrobert.codegpt.settings.GeneralSettings;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import javax.swing.JTextPane;

public final class ChatMessageFontSize {

  public static final int DEFAULT_FONT_SIZE = 0;
  public static final int MIN_FONT_SIZE = 8;
  public static final int MAX_FONT_SIZE = 28;

  private ChatMessageFontSize() {
  }

  public static int increase() {
    return adjust(1);
  }

  public static int decrease() {
    return adjust(-1);
  }

  public static void reset() {
    GeneralSettings.getCurrentState().setChatFontSize(DEFAULT_FONT_SIZE);
  }

  public static boolean canIncrease() {
    return getReferenceFontSize() < MAX_FONT_SIZE;
  }

  public static boolean canDecrease() {
    return getReferenceFontSize() > MIN_FONT_SIZE;
  }

  public static boolean hasCustomFontSize() {
    return getConfiguredFontSize() > DEFAULT_FONT_SIZE;
  }

  public static void applyTo(Container container) {
    for (Component component : container.getComponents()) {
      if (component instanceof JTextPane textPane) {
        applyTo(textPane);
      }
      if (component instanceof Container childContainer) {
        applyTo(childContainer);
      }
    }
  }

  public static void applyTo(JTextPane textPane) {
    int configuredFontSize = getConfiguredFontSize();
    Font baseFont = getBaseFont();
    Font nextFont = configuredFontSize > DEFAULT_FONT_SIZE
        ? baseFont.deriveFont((float) configuredFontSize)
        : baseFont;
    textPane.setFont(nextFont);
    textPane.revalidate();
    textPane.repaint();
  }

  private static int adjust(int delta) {
    int nextFontSize = clamp(getReferenceFontSize() + delta);
    GeneralSettings.getCurrentState().setChatFontSize(nextFontSize);
    return nextFontSize;
  }

  private static int getReferenceFontSize() {
    int configuredFontSize = getConfiguredFontSize();
    return configuredFontSize > DEFAULT_FONT_SIZE
        ? configuredFontSize
        : getBaseFont().getSize();
  }

  private static int getConfiguredFontSize() {
    int configuredFontSize = GeneralSettings.getCurrentState().getChatFontSize();
    return configuredFontSize > DEFAULT_FONT_SIZE ? clamp(configuredFontSize) : DEFAULT_FONT_SIZE;
  }

  private static int clamp(int fontSize) {
    return Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, fontSize));
  }

  private static Font getBaseFont() {
    return JBUI.Fonts.label();
  }
}
