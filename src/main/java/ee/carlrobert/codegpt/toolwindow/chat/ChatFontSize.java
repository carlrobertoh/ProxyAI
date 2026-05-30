package ee.carlrobert.codegpt.toolwindow.chat;

import com.intellij.util.ui.JBUI;
import ee.carlrobert.codegpt.settings.GeneralSettings;
import javax.swing.JTextPane;

public final class ChatFontSize {

  private static final int MIN_OFFSET = -4;
  private static final int MAX_OFFSET = 8;

  private ChatFontSize() {
  }

  public static boolean canAdjust(int delta) {
    int offset = getOffset();
    return delta < 0 ? offset > MIN_OFFSET : offset < MAX_OFFSET;
  }

  public static void adjust(int delta) {
    var state = GeneralSettings.getCurrentState();
    state.setChatFontSizeOffset(clampOffset(state.getChatFontSizeOffset() + delta));
  }

  public static void apply(JTextPane textPane) {
    textPane.setFont(JBUI.Fonts.label().deriveFont((float) getFontSize()));
  }

  private static int getFontSize() {
    return Math.max(8, JBUI.Fonts.label().getSize() + getOffset());
  }

  private static int getOffset() {
    return clampOffset(GeneralSettings.getCurrentState().getChatFontSizeOffset());
  }

  private static int clampOffset(int offset) {
    return Math.max(MIN_OFFSET, Math.min(MAX_OFFSET, offset));
  }
}
