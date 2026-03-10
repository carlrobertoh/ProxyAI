package ee.carlrobert.codegpt.toolwindow.chat;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultCompactActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.ex.CustomComponentAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.ActionLink;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.JBUI.CurrentTheme.Link;
import com.intellij.util.ui.components.BorderLayoutPanel;
import ee.carlrobert.codegpt.CodeGPTKeys;
import ee.carlrobert.codegpt.actions.toolwindow.ClearChatWindowAction;
import ee.carlrobert.codegpt.actions.toolwindow.CreateNewConversationAction;
import ee.carlrobert.codegpt.actions.toolwindow.OpenInEditorAction;
import ee.carlrobert.codegpt.completions.ConversationType;
import ee.carlrobert.codegpt.conversations.Conversation;
import ee.carlrobert.codegpt.conversations.ConversationService;
import ee.carlrobert.codegpt.conversations.message.Message;
import ee.carlrobert.codegpt.psistructure.models.ClassStructure;
import ee.carlrobert.codegpt.settings.service.FeatureType;
import ee.carlrobert.codegpt.settings.models.ModelSettings;
import ee.carlrobert.codegpt.settings.prompts.PersonaPromptDetailsState;
import ee.carlrobert.codegpt.settings.prompts.PromptsConfigurable;
import ee.carlrobert.codegpt.settings.prompts.PromptsSettings;
import ee.carlrobert.codegpt.settings.service.ProviderChangeNotifier;
import ee.carlrobert.codegpt.settings.service.ServiceType;
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTUserDetailsNotifier;
import ee.carlrobert.codegpt.toolwindow.chat.ui.ToolWindowFooterNotification;
import ee.carlrobert.codegpt.toolwindow.chat.ui.textarea.AttachImageNotifier;
import java.awt.CardLayout;
import java.nio.file.Path;
import java.util.Set;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.jetbrains.annotations.NotNull;

public class ChatToolWindowPanel extends SimpleToolWindowPanel {

  private static final String LANDING_CARD = "LANDING";
  private static final String TABS_CARD = "TABS";

  private final ToolWindowFooterNotification imageFileAttachmentNotification;
  private final ActionLink upgradePlanLink;
  private final ChatToolWindowTabbedPane tabbedPane;
  private final JPanel centerPanel;
  private final CardLayout centerLayout;
  private final Project project;
  private ChatToolWindowTabPanel landingPanel;

  public ChatToolWindowPanel(
      @NotNull Project project,
      @NotNull Disposable parentDisposable) {
    super(true);
    this.project = project;
    imageFileAttachmentNotification = new ToolWindowFooterNotification(() ->
        project.putUserData(CodeGPTKeys.IMAGE_ATTACHMENT_FILE_PATH, ""));
    upgradePlanLink = new ActionLink("Upgrade your plan", event -> {
      BrowserUtil.browse("https://tryproxy.io/#pricing");
    });
    upgradePlanLink.setFont(JBUI.Fonts.smallFont());
    upgradePlanLink.setExternalLinkIcon();
    upgradePlanLink.setVisible(false);

    tabbedPane = new ChatToolWindowTabbedPane(parentDisposable);
    tabbedPane.setTabLifecycleCallbacks(this::showTabsView, this::showLandingView);
    centerLayout = new CardLayout();
    centerPanel = new JPanel(centerLayout);
    centerPanel.add(tabbedPane, TABS_CARD);

    initToolWindowPanel(project);
    initializeEventListeners(project);
    showLandingView();
    Disposer.register(parentDisposable, this::disposeLandingPanel);
  }

  private void initializeEventListeners(Project project) {
    var messageBusConnection = project.getMessageBus().connect();
    messageBusConnection.subscribe(AttachImageNotifier.IMAGE_ATTACHMENT_FILE_PATH_TOPIC,
        (AttachImageNotifier) filePath -> imageFileAttachmentNotification.show(
            Path.of(filePath).getFileName().toString(),
            "File path: " + filePath));
    messageBusConnection.subscribe(ProviderChangeNotifier.getTOPIC(),
        (ProviderChangeNotifier) provider -> {
          if (provider == ServiceType.PROXYAI) {
            var userDetails = CodeGPTKeys.CODEGPT_USER_DETAILS.get(project);
            upgradePlanLink.setVisible(userDetails != null);
          } else {
            upgradePlanLink.setVisible(false);
          }
        });
    messageBusConnection.subscribe(CodeGPTUserDetailsNotifier.getCODEGPT_USER_DETAILS_TOPIC(),
        (CodeGPTUserDetailsNotifier) userDetails -> {
          if (userDetails != null) {
            var provider = ModelSettings.getInstance()
                .getServiceForFeature(FeatureType.CHAT);
            upgradePlanLink.setVisible(provider == ServiceType.PROXYAI);
          }
        });
  }

  public ChatToolWindowTabbedPane getChatTabbedPane() {
    return tabbedPane;
  }

  public ChatToolWindowTabPanel createAndSelectNewTabPanel() {
    return createAndSelectConversationTab(ConversationService.getInstance().startConversation(project));
  }

  public ChatToolWindowTabPanel createAndSelectConversationTab(Conversation conversation) {
    var panel = new ChatToolWindowTabPanel(project, conversation);
    tabbedPane.addNewTab(panel);
    showTabsView();
    return panel;
  }

  public void showTabsView() {
    centerLayout.show(centerPanel, TABS_CARD);
  }

  public void requestFocusForInput() {
    tabbedPane.tryFindActiveTabPanel()
        .ifPresentOrElse(
            ChatToolWindowTabPanel::requestFocusForTextArea,
            () -> {
              if (landingPanel != null) {
                landingPanel.requestFocusForTextArea();
              }
            });
  }

  public void showLandingView() {
    disposeLandingPanel();
    landingPanel = createLandingPanel();
    centerPanel.add(landingPanel.getContent(), LANDING_CARD);
    centerLayout.show(centerPanel, LANDING_CARD);
    landingPanel.requestFocusForTextArea();
    centerPanel.revalidate();
    centerPanel.repaint();
  }

  private ChatToolWindowTabPanel createLandingPanel() {
    var conversation = ConversationService.getInstance().createConversation();
    conversation.setProjectPath(project.getBasePath());
    return new ChatToolWindowTabPanel(project, conversation, this::promoteLandingDraftToTab);
  }

  private void promoteLandingDraftToTab(Message message, Set<ClassStructure> psiStructure) {
    var tabPanel = createAndSelectNewTabPanel();
    tabPanel.sendMessage(message, ConversationType.DEFAULT, psiStructure);
  }

  private void disposeLandingPanel() {
    if (landingPanel == null) {
      return;
    }

    centerPanel.remove(landingPanel.getContent());
    Disposer.dispose(landingPanel);
    landingPanel = null;
  }

  public void clearImageNotifications(Project project) {
    imageFileAttachmentNotification.hideNotification();

    project.putUserData(CodeGPTKeys.IMAGE_ATTACHMENT_FILE_PATH, "");
  }

  private void initToolWindowPanel(Project project) {
    Runnable onAddNewTab = () -> {
      createAndSelectNewTabPanel();
      repaint();
      revalidate();
    };

    ApplicationManager.getApplication().invokeLater(() -> {
      setToolbar(new BorderLayoutPanel()
          .addToLeft(createActionToolbar(project, tabbedPane, onAddNewTab).getComponent())
          .addToRight(upgradePlanLink));
      setContent(new BorderLayoutPanel()
          .addToCenter(centerPanel)
          .addToBottom(imageFileAttachmentNotification));
    });
  }

  private ActionToolbar createActionToolbar(
      Project project,
      ChatToolWindowTabbedPane tabbedPane,
      Runnable onAddNewTab) {
    var actionGroup = new DefaultCompactActionGroup("TOOLBAR_ACTION_GROUP", false);
    actionGroup.add(new CreateNewConversationAction(onAddNewTab));
    actionGroup.add(
        new ClearChatWindowAction(() -> tabbedPane.resetCurrentlyActiveTabPanel(project)));
    actionGroup.addSeparator();
    actionGroup.add(new OpenInEditorAction());
    actionGroup.addSeparator();
    actionGroup.add(new SelectedPersonaActionLink(project));

    var toolbar = ActionManager.getInstance()
        .createActionToolbar("NAVIGATION_BAR_TOOLBAR", actionGroup, true);
    toolbar.setTargetComponent(this);
    return toolbar;
  }
  
  private static class SelectedPersonaActionLink extends DumbAwareAction implements
      CustomComponentAction {

    private final Project project;

    SelectedPersonaActionLink(Project project) {
      this.project = project;
    }

    private void showPromptsSettingsDialog() {
      ShowSettingsUtil.getInstance()
          .showSettingsDialog(project, PromptsConfigurable.class);
    }

    @Override
    @NotNull
    public JComponent createCustomComponent(
        @NotNull Presentation presentation,
        @NotNull String place) {
      var selectedPersona = getSelectedPersona();
      var personaName = selectedPersona.getName();
      if (personaName == null) {
        personaName = "No persona selected";
      }

      var link = new ActionLink(personaName, (e) -> {
        showPromptsSettingsDialog();
      });
      link.setExternalLinkIcon();
      if (selectedPersona.getDisabled()) {
        link.setToolTipText("Persona is disabled");
        link.setForeground(JBUI.CurrentTheme.Label.disabledForeground());
      }
      link.setFont(JBUI.Fonts.smallFont());
      link.setBorder(JBUI.Borders.empty(0, 4));
      return link;
    }

    @Override
    public void updateCustomComponent(
        @NotNull JComponent component,
        @NotNull Presentation presentation) {
      if (component instanceof ActionLink actionLink) {
        var selectedPersona = getSelectedPersona();
        var personaName = selectedPersona.getName();
        if (personaName == null) {
          personaName = "No persona selected";
        }

        if (selectedPersona.getDisabled()) {
          actionLink.setText(personaName + " (disabled)");
          actionLink.setForeground(Link.Foreground.DISABLED);
        } else {
          actionLink.setText(personaName);
          actionLink.setForeground(Link.Foreground.ENABLED);
        }
      }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
    }

    private PersonaPromptDetailsState getSelectedPersona() {
      return ApplicationManager.getApplication().getService(PromptsSettings.class)
          .getState()
          .getPersonas()
          .getSelectedPersona();
    }
  }
}
