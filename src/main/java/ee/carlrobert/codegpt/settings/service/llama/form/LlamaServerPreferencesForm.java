package ee.carlrobert.codegpt.settings.service.llama.form;

import static ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey.LlamaApiKey;
import static ee.carlrobert.codegpt.settings.service.llama.LlamaSettings.isModelExists;
import static ee.carlrobert.codegpt.ui.UIUtil.createComment;
import static ee.carlrobert.codegpt.ui.UIUtil.createForm;
import static ee.carlrobert.codegpt.ui.UIUtil.withEmptyLeftBorder;

import com.intellij.icons.AllIcons.Actions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.SystemInfoRt;
import com.intellij.ui.PortField;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.fields.IntegerField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UI;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.codecompletions.InfillPromptTemplate;
import ee.carlrobert.codegpt.completions.llama.LlamaServerAgent;
import ee.carlrobert.codegpt.completions.llama.LlamaServerStartupParams;
import ee.carlrobert.codegpt.completions.llama.PromptTemplate;
import ee.carlrobert.codegpt.credentials.CredentialsStore;
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey;
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings;
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettingsState;
import ee.carlrobert.codegpt.ui.OverlayUtil;
import ee.carlrobert.codegpt.ui.UIUtil;
import ee.carlrobert.codegpt.ui.UIUtil.RadioButtonWithLayout;
import ee.carlrobert.codegpt.ui.URLTextField;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;

public class LlamaServerPreferencesForm {

  private static final String RUN_LOCAL_SERVER_FORM_CARD_CODE = "RunLocalServerSettings";
  private static final String USE_EXISTING_SERVER_FORM_CARD_CODE = "UseExistingServerSettings";

  private final LlamaModelPreferencesForm llamaModelPreferencesForm;

  private final JBRadioButton runLocalServerRadioButton;
  private final JBRadioButton useExistingServerRadioButton;
  private final JBTextField baseHostField;
  private final JBPasswordField apiKeyField;
  private final PortField portField;
  private final IntegerField maxTokensField;
  private final IntegerField threadsField;
  private final JBTextField additionalParametersField;
  private final JBTextField additionalBuildParametersField;
  private final JBTextField additionalEnvironmentVariablesField;
  private final ChatPromptTemplatePanel remotePromptTemplatePanel;
  private final InfillPromptTemplatePanel infillPromptTemplatePanel;

  public LlamaServerPreferencesForm(LlamaSettingsState settings) {
    var llamaServerAgent = ApplicationManager.getApplication().getService(LlamaServerAgent.class);
    var serverRunning = llamaServerAgent.isServerRunning();
    portField = new PortField(settings.getServerPort());
    portField.setEnabled(!serverRunning);

    maxTokensField = new IntegerField("max_tokens", 256, 4096);
    maxTokensField.setColumns(12);
    maxTokensField.setValue(settings.getContextSize());
    maxTokensField.setEnabled(!serverRunning);

    threadsField = new IntegerField("threads", 1, 256);
    threadsField.setColumns(12);
    threadsField.setValue(settings.getThreads());
    threadsField.setEnabled(!serverRunning);

    additionalParametersField = new JBTextField(settings.getAdditionalParameters(), 30);
    additionalParametersField.setEnabled(!serverRunning);

    additionalBuildParametersField = new JBTextField(settings.getAdditionalBuildParameters(), 30);
    additionalBuildParametersField.setEnabled(!serverRunning);

    additionalEnvironmentVariablesField = new JBTextField(
        settings.getAdditionalEnvironmentVariables(), 30);
    additionalEnvironmentVariablesField.setEnabled(!serverRunning);

    baseHostField = new URLTextField(settings.getBaseHost(), 30);
    apiKeyField = new JBPasswordField();
    apiKeyField.setColumns(30);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      var apiKey = CredentialsStore.getCredential(CredentialKey.LlamaApiKey.INSTANCE);
      SwingUtilities.invokeLater(() -> apiKeyField.setText(apiKey));
    });

    llamaModelPreferencesForm = new LlamaModelPreferencesForm();
    runLocalServerRadioButton = new JBRadioButton("Run local server",
        settings.isRunLocalServer());
    useExistingServerRadioButton = new JBRadioButton("Use remote server",
        !settings.isRunLocalServer());

    runLocalServerRadioButton.setEnabled(SystemInfoRt.isUnix);
    runLocalServerRadioButton.setVisible(SystemInfoRt.isUnix);

    remotePromptTemplatePanel = new ChatPromptTemplatePanel(
        settings.getRemoteModelPromptTemplate(), true);
    infillPromptTemplatePanel = new InfillPromptTemplatePanel(
        settings.getRemoteModelInfillPromptTemplate(), true
    );
  }

  public JPanel getForm() {
    var llamaServerAgent =
        ApplicationManager.getApplication().getService(LlamaServerAgent.class);
    return createForm(Map.of(
        RUN_LOCAL_SERVER_FORM_CARD_CODE, new RadioButtonWithLayout(runLocalServerRadioButton,
            createRunLocalServerForm(llamaServerAgent)),
        USE_EXISTING_SERVER_FORM_CARD_CODE, new RadioButtonWithLayout(useExistingServerRadioButton,
            createUseExistingServerForm())
    ), SystemInfoRt.isUnix ? (runLocalServerRadioButton.isSelected()
        ? RUN_LOCAL_SERVER_FORM_CARD_CODE
        : USE_EXISTING_SERVER_FORM_CARD_CODE) : USE_EXISTING_SERVER_FORM_CARD_CODE);
  }

  public void resetForm(LlamaSettingsState state) {
    llamaModelPreferencesForm.resetForm(state);

    remotePromptTemplatePanel.setPromptTemplate(state.getLocalModelPromptTemplate()); // ?
    infillPromptTemplatePanel.setPromptTemplate(state.getLocalModelInfillPromptTemplate());
    runLocalServerRadioButton.setSelected(state.isRunLocalServer());
    baseHostField.setText(state.getBaseHost());
    portField.setNumber(state.getServerPort());
    maxTokensField.setValue(state.getContextSize());
    threadsField.setValue(state.getThreads());
    additionalParametersField.setText(state.getAdditionalParameters());
    additionalBuildParametersField.setText(state.getAdditionalBuildParameters());
    additionalEnvironmentVariablesField.setText(state.getAdditionalEnvironmentVariables());
    remotePromptTemplatePanel.setPromptTemplate(state.getRemoteModelPromptTemplate()); // ?
    infillPromptTemplatePanel.setPromptTemplate(state.getRemoteModelInfillPromptTemplate());
    apiKeyField.setText(CredentialsStore.getCredential(LlamaApiKey.INSTANCE));
  }

  public JComponent createUseExistingServerForm() {
    var apiKeyFieldPanel = UI.PanelFactory.panel(apiKeyField)
        .withLabel(CodeGPTBundle.get("settingsConfigurable.shared.apiKey.label"))
        .resizeX(false)
        .withComment(
            CodeGPTBundle.get("settingsConfigurable.shared.apiKey.comment"))
        .withCommentHyperlinkListener(UIUtil::handleHyperlinkClicked)
        .createPanel();
    return withEmptyLeftBorder(FormBuilder.createFormBuilder()
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.service.llama.baseHost.label"),
            baseHostField)
        .addComponentToRightColumn(
            createComment("settingsConfigurable.service.llama.baseHost.comment"))
        .addLabeledComponent(CodeGPTBundle.get("shared.promptTemplate"),
            remotePromptTemplatePanel)
        .addComponentToRightColumn(remotePromptTemplatePanel.getPromptTemplateHelpText())
        .addLabeledComponent(CodeGPTBundle.get("shared.infillPromptTemplate"),
            infillPromptTemplatePanel)
        .addComponentToRightColumn(infillPromptTemplatePanel.getPromptTemplateHelpText())
        .addComponent(new TitledSeparator(
            CodeGPTBundle.get("settingsConfigurable.shared.authentication.title")))
        .addComponent(withEmptyLeftBorder(apiKeyFieldPanel))
        .getPanel());
  }

  public JComponent createRunLocalServerForm(LlamaServerAgent llamaServerAgent) {
    var serverProgressPanel = new ServerProgressPanel();
    serverProgressPanel.setBorder(JBUI.Borders.emptyRight(16));
    return withEmptyLeftBorder(FormBuilder.createFormBuilder()
        .addComponent(new TitledSeparator(
            CodeGPTBundle.get("settingsConfigurable.service.llama.modelPreferences.title")))
        .addComponent(withEmptyLeftBorder(llamaModelPreferencesForm.getForm()))
        .addComponent(new TitledSeparator("Server Configuration"))
        .addComponent(withEmptyLeftBorder(FormBuilder.createFormBuilder()
            .addLabeledComponent(
                CodeGPTBundle.get("shared.port"),
                JBUI.Panels.simplePanel()
                    .addToLeft(portField)
                    .addToRight(JBUI.Panels.simplePanel()
                        .addToCenter(serverProgressPanel)
                        .addToRight(getServerButton(llamaServerAgent, serverProgressPanel))))
            .addVerticalGap(4)
            .addLabeledComponent(
                CodeGPTBundle.get("settingsConfigurable.service.llama.contextSize.label"),
                maxTokensField)
            .addComponentToRightColumn(
                createComment("settingsConfigurable.service.llama.contextSize.comment"))
            .addLabeledComponent(
                CodeGPTBundle.get("settingsConfigurable.service.llama.threads.label"),
                threadsField)
            .addComponentToRightColumn(
                createComment("settingsConfigurable.service.llama.threads.comment"))
            .addLabeledComponent(
                CodeGPTBundle.get("settingsConfigurable.service.llama.additionalParameters.label"),
                additionalParametersField)
            .addComponentToRightColumn(
                createComment(
                    "settingsConfigurable.service.llama.additionalParameters.comment"))
            .addLabeledComponent(
                CodeGPTBundle.get(
                    "settingsConfigurable.service.llama.additionalBuildParameters.label"),
                additionalBuildParametersField)
            .addComponentToRightColumn(
                createComment(
                    "settingsConfigurable.service.llama.additionalBuildParameters.comment"))
            .addVerticalGap(4)
            .addLabeledComponent(
                CodeGPTBundle.get(
                    "settingsConfigurable.service.llama.additionalEnvironmentVariables.label"),
                additionalEnvironmentVariablesField)
            .addComponentToRightColumn(
                createComment(
                    "settingsConfigurable.service.llama.additionalEnvironmentVariables.comment"))
            .addVerticalGap(4)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel()))
        .getPanel());
  }

  private JButton getServerButton(
      LlamaServerAgent llamaServerAgent,
      ServerProgressPanel serverProgressPanel) {
    llamaServerAgent.setActiveServerProgressPanel(serverProgressPanel);
    var serverRunning = llamaServerAgent.isServerRunning();
    var serverButton = new JButton();
    serverButton.setText(serverRunning
        ? CodeGPTBundle.get("settingsConfigurable.service.llama.stopServer.label")
        : CodeGPTBundle.get("settingsConfigurable.service.llama.startServer.label"));
    serverButton.setIcon(serverRunning ? Actions.Suspend : Actions.Execute);
    serverButton.addActionListener(event -> {
      if (!validateModelConfiguration()) {
        return;
      }

      if (llamaServerAgent.isServerRunning()) {
        enableForm(serverButton, serverProgressPanel);
        llamaServerAgent.stopAgent();
      } else {
        disableForm(serverButton, serverProgressPanel);
        llamaServerAgent.startAgent(
            new LlamaServerStartupParams(
                llamaModelPreferencesForm.getActualModelPath(),
                getContextSize(),
                getThreads(),
                getServerPort(),
                getListOfAdditionalParameters(),
                getListOfAdditionalBuildParameters(),
                getMapOfAdditionalEnvironmentVariables()
            ),
            serverProgressPanel,
            () -> {
              setFormEnabled(false);
              serverProgressPanel.displayComponent(new JBLabel(
                  CodeGPTBundle.get("settingsConfigurable.service.llama.progress.serverRunning"),
                  Actions.Checked,
                  SwingConstants.LEADING));
            },
            (activeServerProgressPanel) -> {
              setFormEnabled(true);
              serverButton.setText(
                  CodeGPTBundle.get("settingsConfigurable.service.llama.startServer.label"));
              serverButton.setIcon(Actions.Execute);
              activeServerProgressPanel.displayComponent(new JBLabel(
                  CodeGPTBundle.get("settingsConfigurable.service.llama.progress.serverStopped"),
                  Actions.Cancel,
                  SwingConstants.LEADING));
            });
      }
    });
    return serverButton;
  }

  private boolean validateModelConfiguration() {
    return validateCustomModelPath() && validateSelectedModel();
  }

  private boolean validateCustomModelPath() {
    if (llamaModelPreferencesForm.isUseCustomLlamaModel()) {
      var customModelPath = llamaModelPreferencesForm.getCustomLlamaModelPath();
      if (customModelPath == null || customModelPath.isEmpty()) {
        OverlayUtil.showBalloon(
            CodeGPTBundle.get("validation.error.fieldRequired"),
            MessageType.ERROR,
            llamaModelPreferencesForm.getBrowsableCustomModelTextField());
        return false;
      }
    }
    return true;
  }

  private boolean validateSelectedModel() {
    if (!llamaModelPreferencesForm.isUseCustomLlamaModel()
        && !isModelExists(llamaModelPreferencesForm.getSelectedModel())) {
      OverlayUtil.showBalloon(
          CodeGPTBundle.get("settingsConfigurable.service.llama.overlay.modelNotDownloaded.text"),
          MessageType.ERROR,
          llamaModelPreferencesForm.getHuggingFaceModelComboBox());
      return false;
    }
    return true;
  }

  private void enableForm(JButton serverButton, ServerProgressPanel progressPanel) {
    setFormEnabled(true);
    serverButton.setText(
        CodeGPTBundle.get("settingsConfigurable.service.llama.startServer.label"));
    serverButton.setIcon(Actions.Execute);
    progressPanel.displayText(
        CodeGPTBundle.get("settingsConfigurable.service.llama.progress.stoppingServer"));
  }

  private void disableForm(JButton serverButton, ServerProgressPanel progressPanel) {
    setFormEnabled(false);
    serverButton.setText(
        CodeGPTBundle.get("settingsConfigurable.service.llama.stopServer.label"));
    serverButton.setIcon(Actions.Suspend);
    progressPanel.displayText(
        CodeGPTBundle.get("settingsConfigurable.service.llama.progress.startingServer"));
  }

  private void setFormEnabled(boolean enabled) {
    llamaModelPreferencesForm.enableFields(enabled);
    portField.setEnabled(enabled);
    maxTokensField.setEnabled(enabled);
    threadsField.setEnabled(enabled);
    additionalParametersField.setEnabled(enabled);
    additionalBuildParametersField.setEnabled(enabled);
    additionalEnvironmentVariablesField.setEnabled(enabled);
  }

  public boolean isRunLocalServer() {
    return runLocalServerRadioButton.isSelected();
  }

  public String getBaseHost() {
    return baseHostField.getText();
  }

  public int getServerPort() {
    return portField.getNumber();
  }

  public LlamaModelPreferencesForm getLlamaModelPreferencesForm() {
    return llamaModelPreferencesForm;
  }

  public int getContextSize() {
    return maxTokensField.getValue();
  }

  public void setThreads(int threads) {
    threadsField.setValue(threads);
  }

  public int getThreads() {
    return threadsField.getValue();
  }

  public String getAdditionalParameters() {
    return additionalParametersField.getText();
  }

  public List<String> getListOfAdditionalParameters() {
    return LlamaSettings.getAdditionalParametersList(additionalParametersField.getText());
  }

  public String getAdditionalBuildParameters() {
    return additionalBuildParametersField.getText();
  }

  public List<String> getListOfAdditionalBuildParameters() {
    return LlamaSettings.getAdditionalParametersList(additionalBuildParametersField.getText());
  }

  public String getAdditionalEnvironmentVariables() {
    return additionalEnvironmentVariablesField.getText();
  }

  public Map<String, String> getMapOfAdditionalEnvironmentVariables() {
    return LlamaSettings.getAdditionalEnvironmentVariablesMap(
        additionalEnvironmentVariablesField.getText());
  }

  public PromptTemplate getPromptTemplate() {
    var template = isRunLocalServer() ? llamaModelPreferencesForm.getPromptTemplate()
        : remotePromptTemplatePanel.getPromptTemplate();
    return template == null ? PromptTemplate.CODE_QWEN : template;
  }

  public @Nullable String getApiKey() {
    var apiKey = new String(apiKeyField.getPassword());
    return apiKey.isEmpty() ? null : apiKey;
  }

  public InfillPromptTemplate getInfillPromptTemplate() {
    return infillPromptTemplatePanel.getPromptTemplate();
  }
}
