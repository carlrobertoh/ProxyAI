package ee.carlrobert.codegpt.settings.service.openai;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.credentials.CredentialsStore;
import ee.carlrobert.codegpt.credentials.CredentialsStore.CredentialKey;
import ee.carlrobert.codegpt.ui.UIUtil;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;

public class OpenAISettingsForm {

  private final JBPasswordField apiKeyField;
  private final JBTextField organizationField;
  private final JBCheckBox codeCompletionsEnabledCheckBox;

  public OpenAISettingsForm(OpenAISettingsState settings) {
    apiKeyField = new JBPasswordField();
    apiKeyField.setColumns(30);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      var apiKey = CredentialsStore.getCredential(CredentialKey.OpenaiApiKey.INSTANCE);
      SwingUtilities.invokeLater(() -> apiKeyField.setText(apiKey));
    });
    organizationField = new JBTextField(settings.getOrganization(), 30);
    codeCompletionsEnabledCheckBox = new JBCheckBox(
        CodeGPTBundle.get("codeCompletionsForm.enableFeatureText"),
        settings.isCodeCompletionsEnabled());
  }

  public JPanel getForm() {
    return FormBuilder.createFormBuilder()
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.shared.apiKey.label"), apiKeyField)
        .addComponentToRightColumn(
            UIUtil.createComment("settingsConfigurable.service.openai.apiKey.comment")
        )
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.service.openai.organization.label"),
            organizationField)
        .addComponentToRightColumn(
            UIUtil.createComment("settingsConfigurable.section.openai.organization.comment")
        )
        .addComponent(codeCompletionsEnabledCheckBox)
        .addComponentFillVertically(new JPanel(), 0)
        .getPanel();
  }

  public @Nullable String getApiKey() {
    var apiKey = new String(apiKeyField.getPassword());
    return apiKey.isEmpty() ? null : apiKey;
  }

  public OpenAISettingsState getCurrentState() {
    var state = new OpenAISettingsState();
    state.setOrganization(organizationField.getText());
    state.setCodeCompletionsEnabled(codeCompletionsEnabledCheckBox.isSelected());
    return state;
  }

  public void resetForm() {
    var state = OpenAISettings.getCurrentState();
    apiKeyField.setText(CredentialsStore.getCredential(CredentialKey.OpenaiApiKey.INSTANCE));
    organizationField.setText(state.getOrganization());
    codeCompletionsEnabledCheckBox.setSelected(state.isCodeCompletionsEnabled());
  }
}
