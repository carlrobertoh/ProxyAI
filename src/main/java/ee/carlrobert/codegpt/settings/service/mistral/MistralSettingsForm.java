package ee.carlrobert.codegpt.settings.service.mistral;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UI;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.credentials.CredentialsStore;
import ee.carlrobert.codegpt.ui.UIUtil;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.Nullable;

public class MistralSettingsForm {

  private final JBPasswordField apiKeyField;

  public MistralSettingsForm(MistralSettingsState settings) {
    apiKeyField = new JBPasswordField();
    apiKeyField.setColumns(30);
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      var apiKey = CredentialsStore.getCredential(
          CredentialsStore.CredentialKey.MistralApiKey.INSTANCE
      );
      SwingUtilities.invokeLater(() -> apiKeyField.setText(apiKey));
    });
  }

  public JPanel getForm() {
    return FormBuilder.createFormBuilder()
        .addComponent(UI.PanelFactory.grid()
            .add(UI.PanelFactory.panel(apiKeyField)
                .withLabel(CodeGPTBundle.get("settingsConfigurable.shared.apiKey.label"))
                .resizeX(false)
                .withComment(
                    "You can find the API key in your <a href=\"https://console.mistral.ai/api-keys\">Mistral Console</a>.")
                .withCommentHyperlinkListener(UIUtil::handleHyperlinkClicked))
            .createPanel())
        .addComponentFillVertically(new JPanel(), 0)
        .getPanel();
  }

  public MistralSettingsState getCurrentState() {
    var state = new MistralSettingsState();
    return state;
  }

  public void resetForm() {
    var state = MistralSettings.getCurrentState();
    apiKeyField.setText(
        CredentialsStore.getCredential(CredentialsStore.CredentialKey.MistralApiKey.INSTANCE)
    );
  }

  public @Nullable String getApiKey() {
    var apiKey = new String(apiKeyField.getPassword());
    return apiKey.isEmpty() ? null : apiKey;
  }
}