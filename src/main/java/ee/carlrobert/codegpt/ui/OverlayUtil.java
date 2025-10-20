package ee.carlrobert.codegpt.ui;

import static com.intellij.notification.NotificationType.INFORMATION;
import static com.intellij.openapi.ui.Messages.CANCEL;
import static com.intellij.openapi.ui.Messages.OK;
import static ee.carlrobert.codegpt.Icons.Default;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import com.intellij.execution.ExecutionBundle;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DoNotAskOption;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.Balloon.Position;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.awt.RelativePoint;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.conversations.ConversationsState;
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings;
import ee.carlrobert.codegpt.util.EditorUtil;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import javax.swing.JComponent;
import org.jetbrains.annotations.NotNull;

public class OverlayUtil {

  public static final String NOTIFICATION_GROUP_ID = "proxyai.notification.group";
  public static final String NOTIFICATION_GROUP_STICKY_ID = "proxyai.notification.sticky.group";

  private OverlayUtil() {
  }

  public static Notification getDefaultNotification(
      @NotNull String content, @NotNull AnAction... actions) {
    return getDefaultNotification(content, INFORMATION, actions);
  }

  public static Notification getDefaultNotification(
      @NotNull String content, @NotNull NotificationType type, @NotNull AnAction... actions) {
    var notification =
        new Notification(NOTIFICATION_GROUP_ID, CodeGPTBundle.get("project.label"), content, type);
    notification.setListener(new NotificationListener.UrlOpeningListener(true));
    Arrays.asList(actions).forEach(notification::addAction);
    return notification;
  }

  public static Notification getStickyNotification(
      @NotNull String content, @NotNull AnAction... actions) {
    return getStickyNotification(content, INFORMATION, actions);
  }

  public static Notification getStickyNotification(
      @NotNull String content, @NotNull NotificationType type, @NotNull AnAction... actions) {
    var notification = new Notification(
        NOTIFICATION_GROUP_STICKY_ID,
        CodeGPTBundle.get("project.label"),
        content,
        type);
    Arrays.asList(actions).forEach(notification::addAction);
    return notification;
  }

  public static Notification showNotification(
      @NotNull String content, @NotNull AnAction... actions) {
    return showNotification(content, INFORMATION, actions);
  }

  public static Notification showNotification(
      @NotNull String content, @NotNull NotificationType type, @NotNull AnAction... actions) {
    return notify(getDefaultNotification(content, type, actions));
  }

  public static Notification stickyNotification(
      @NotNull String content, @NotNull AnAction... actions) {
    return stickyNotification(content, INFORMATION, actions);
  }

  public static Notification stickyNotification(
      @NotNull String content, @NotNull NotificationType type, @NotNull AnAction... actions) {
    return notify(getStickyNotification(content, type, actions));
  }

  public static @NotNull Notification notify(
      @NotNull Notification notification, @NotNull AnAction... actions) {
    Arrays.asList(actions).forEach(notification::addAction);
    Notifications.Bus.notify(notification);
    return notification;
  }

  public static int showDeleteConversationDialog() {
    return Messages.showYesNoDialog(
        CodeGPTBundle.get("dialog.deleteConversation.description"),
        CodeGPTBundle.get("dialog.deleteConversation.title"),
        Default);
  }

  public static int showTokenLimitExceededDialog(Project project) {
    return MessageDialogBuilder.okCancel(
            CodeGPTBundle.get("dialog.tokenLimitExceeded.title"),
            CodeGPTBundle.get("dialog.tokenLimitExceeded.description"))
        .yesText(CodeGPTBundle.get("dialog.continue"))
        .noText(CodeGPTBundle.get("shared.cancel"))
        .icon(Default)
        .doNotAsk(new DoNotAskOption.Adapter() {
          @Override
          public void rememberChoice(boolean isSelected, int exitCode) {
            if (isSelected) {
              ConversationsState.getInstance(project).discardAllTokenLimits();
            }
          }

          @NotNull
          @Override
          public String getDoNotShowMessage() {
            return ExecutionBundle.message("don.t.ask.again");
          }

          @Override
          public boolean shouldSaveOptionsOnCancel() {
            return true;
          }
        })
        .guessWindowAndAsk() ? OK : CANCEL;
  }

  public static int showTokenSoftLimitWarningDialog(int tokenCount) {
    return MessageDialogBuilder.okCancel(
            CodeGPTBundle.get("dialog.tokenSoftLimitExceeded.title"),
            format(CodeGPTBundle.get("dialog.tokenSoftLimitExceeded.description"), tokenCount))
        .yesText(CodeGPTBundle.get("dialog.continue"))
        .noText(CodeGPTBundle.get("shared.cancel"))
        .icon(Default)
        .doNotAsk(new DoNotAskOption.Adapter() {
          @Override
          public void rememberChoice(boolean isSelected, int exitCode) {
            if (isSelected) {
              ConfigurationSettings.getState().setIgnoreGitCommitTokenLimit(true);
            }
          }

          @NotNull
          @Override
          public String getDoNotShowMessage() {
            return ExecutionBundle.message("don.t.ask.again");
          }

          @Override
          public boolean shouldSaveOptionsOnCancel() {
            return true;
          }
        })
        .guessWindowAndAsk() ? OK : CANCEL;
  }

  public static void showSelectedEditorSelectionWarning(AnActionEvent event) {
    var mouseEvent = (MouseEvent) event.getInputEvent();
    if (mouseEvent != null) {
      var locationOnScreen = mouseEvent.getLocationOnScreen();
      locationOnScreen.y = locationOnScreen.y - 16;
      showSelectedEditorSelectionWarning(requireNonNull(event.getProject()), locationOnScreen);
    }
  }

  public static void showSelectedEditorSelectionWarning(
      @NotNull Project project,
      Point locationOnScreen) {
    showWarningBalloon(
        EditorUtil.getSelectedEditor(project) == null
            ? "Unable to locate a selected editor"
            : "Please select a target code before proceeding",
        locationOnScreen);
  }

  public static void showWarningBalloon(String content, Point locationOnScreen) {
    showBalloon(content, MessageType.WARNING, locationOnScreen);
  }

  public static void showInfoBalloon(String content, Point locationOnScreen) {
    showBalloon(content, MessageType.INFO, locationOnScreen);
  }

  private static void showBalloon(String content, MessageType messageType, Point locationOnScreen) {
    JBPopupFactory.getInstance()
        .createHtmlTextBalloonBuilder(content, messageType, null)
        .setFadeoutTime(2500)
        .createBalloon()
        .show(RelativePoint.fromScreen(locationOnScreen), Balloon.Position.above);
  }

  public static void showBalloon(String content, MessageType messageType, JComponent component) {
    JBPopupFactory.getInstance()
        .createHtmlTextBalloonBuilder(content, messageType, null)
        .setFadeoutTime(2500)
        .createBalloon()
        .show(RelativePoint.getSouthOf(component), Position.below);
  }

  public static void showClosableBalloon(String content, MessageType messageType,
      JComponent component) {
    JBPopupFactory.getInstance()
        .createHtmlTextBalloonBuilder(content, messageType, null)
        .setCloseButtonEnabled(true)
        .createBalloon()
        .show(RelativePoint.getSouthOf(component), Position.below);
  }
}
