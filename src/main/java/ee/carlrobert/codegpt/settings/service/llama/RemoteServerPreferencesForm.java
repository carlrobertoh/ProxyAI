package ee.carlrobert.codegpt.settings.service.llama;

import static ee.carlrobert.codegpt.ui.UIUtil.addApiKeyPanel;
import static ee.carlrobert.codegpt.ui.UIUtil.createComment;
import static ee.carlrobert.codegpt.ui.UIUtil.withEmptyLeftBorder;

import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.credentials.ServiceCredentialsManager;
import ee.carlrobert.codegpt.settings.service.ServiceType;
import ee.carlrobert.codegpt.settings.state.llama.LlamaRemoteSettings;
import javax.swing.JComponent;

/**
 * Form containing fields for all {@link LlamaRemoteSettings}
 */
public class RemoteServerPreferencesForm {

  private final JBTextField baseHostField;
  private final RemoteModelPreferencesForm remoteModelPreferencesForm;
  private final ServiceType servicePrefix;
  private JBPasswordField apiKeyField;

  private final ServiceCredentialsManager credentialsManager;

  public RemoteServerPreferencesForm(LlamaRemoteSettings settings, ServiceType serviceType) {
    this.credentialsManager = settings.getCredentialsManager();
    this.servicePrefix = serviceType;
    baseHostField = new JBTextField(settings.getBaseHost(), 30);
    remoteModelPreferencesForm = new RemoteModelPreferencesForm(settings, serviceType);
    if (credentialsManager.providesApiKey()) {
      apiKeyField = new JBPasswordField();
      apiKeyField.setColumns(30);
      apiKeyField.setText(credentialsManager.getApiKey());
    }
  }

  public JComponent getForm() {
    FormBuilder formBuilder = FormBuilder.createFormBuilder()
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.service.ollama.baseHost.label"),
            baseHostField)
        .addComponentToRightColumn(
            createComment(
                String.format("settingsConfigurable.service.%s.baseHost.comment", servicePrefix.getBundlePrefix())))
        .addComponent(new TitledSeparator(
            CodeGPTBundle.get("settingsConfigurable.service.ollama.modelPreferences.title")))
        .addComponent(withEmptyLeftBorder(remoteModelPreferencesForm.getForm()));
    if (credentialsManager.providesApiKey()) {
      addApiKeyPanel(credentialsManager.getApiKey(), formBuilder, apiKeyField);
    }
    return withEmptyLeftBorder(formBuilder
        .getPanel());
  }

  public void setFormEnabled(boolean enabled) {
    remoteModelPreferencesForm.enableFields(enabled);
    baseHostField.setEnabled(enabled);
  }

  public void setRemoteSettings(LlamaRemoteSettings settings) {
    remoteModelPreferencesForm.setPromptTemplate(settings.getPromptTemplate());
    baseHostField.setText(settings.getBaseHost());
    ServiceCredentialsManager credentialsManager = settings.getCredentialsManager();
    if(credentialsManager.providesApiKey()){
      apiKeyField.setText(credentialsManager.getApiKey());
    }
  }

  public LlamaRemoteSettings getRemoteSettings() {
    LlamaRemoteSettings remoteSettings = new LlamaRemoteSettings(
        remoteModelPreferencesForm.getPromptTemplate(),
        baseHostField.getText()
    );
    remoteSettings.setCredentialsManager(credentialsManager);
    return remoteSettings;
  }

  public String getApiKey() {
    return apiKeyField != null ? new String(apiKeyField.getPassword()) : null;
  }

  public void setApiKey(String apiKey) {
    apiKeyField.setText(apiKey);
  }
}
