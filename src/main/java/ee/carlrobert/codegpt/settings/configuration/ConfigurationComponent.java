package ee.carlrobert.codegpt.settings.configuration;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.ui.ComponentValidator;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.fields.IntegerField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UI;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.ui.UIUtil;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public class ConfigurationComponent {

  private final JPanel mainPanel;
  private final JBCheckBox checkForPluginUpdatesCheckBox;
  private final JBCheckBox checkForNewScreenshotsCheckBox;
  private final JBCheckBox methodNameGenerationCheckBox;
  private final JBCheckBox autoFormattingCheckBox;
  private final JBCheckBox autocompletionPostProcessingCheckBox;
  private final JBCheckBox autocompletionContextAwareCheckBox;
  private final JBCheckBox autocompletionGitContextCheckBox;
  private final IntegerField maxTokensField;
  private final JBTextField temperatureField;
  private final ChatCompletionConfigurationForm chatCompletionForm;

  public ConfigurationComponent(
      Disposable parentDisposable,
      ConfigurationSettingsState configuration) {
    temperatureField = new JBTextField(12);
    temperatureField.setText(String.valueOf(configuration.getTemperature()));

    var temperatureFieldValidator = createTemperatureInputValidator(parentDisposable,
        temperatureField);
    temperatureField.getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void insertUpdate(DocumentEvent e) {
        temperatureFieldValidator.revalidate();
      }

      @Override
      public void removeUpdate(DocumentEvent e) {
        temperatureFieldValidator.revalidate();
      }

      @Override
      public void changedUpdate(DocumentEvent e) {
        temperatureFieldValidator.revalidate();
      }
    });

    maxTokensField = new IntegerField();
    maxTokensField.setColumns(12);
    maxTokensField.setValue(configuration.getMaxTokens());

    checkForPluginUpdatesCheckBox = new JBCheckBox(
        CodeGPTBundle.get("configurationConfigurable.checkForPluginUpdates.label"),
        configuration.getCheckForPluginUpdates());
    checkForNewScreenshotsCheckBox = new JBCheckBox(
        CodeGPTBundle.get("configurationConfigurable.checkForNewScreenshots.label"),
        configuration.getCheckForNewScreenshots());
    methodNameGenerationCheckBox = new JBCheckBox(
        CodeGPTBundle.get("configurationConfigurable.enableMethodNameGeneration.label"),
        configuration.getMethodNameGenerationEnabled());
    autoFormattingCheckBox = new JBCheckBox(
        CodeGPTBundle.get("configurationConfigurable.autoFormatting.label"),
        configuration.getAutoFormattingEnabled());
    autocompletionPostProcessingCheckBox = new JBCheckBox(
        CodeGPTBundle.get("configurationConfigurable.autocompletionPostProcessing.label"),
        configuration.getAutocompletionPostProcessingEnabled()
    );
    autocompletionContextAwareCheckBox = new JBCheckBox(
        CodeGPTBundle.get("configurationConfigurable.autocompletionContextAwareCheckBox.label"),
        configuration.getAutocompletionContextAwareEnabled()
    );
    autocompletionGitContextCheckBox = new JBCheckBox(
        CodeGPTBundle.get("configurationConfigurable.autocompletionGitContextCheckBox.label"),
        configuration.getAutocompletionGitContextEnabled()
    );
    chatCompletionForm = new ChatCompletionConfigurationForm();

    mainPanel = FormBuilder.createFormBuilder()
        .addComponent(checkForPluginUpdatesCheckBox)
        .addComponent(checkForNewScreenshotsCheckBox)
        .addComponent(methodNameGenerationCheckBox)
        .addComponent(autoFormattingCheckBox)
        .addComponent(autocompletionPostProcessingCheckBox)
        .addComponent(autocompletionContextAwareCheckBox)
        .addComponent(autocompletionGitContextCheckBox)
        .addVerticalGap(4)
        .addComponent(new TitledSeparator(
            CodeGPTBundle.get("configurationConfigurable.section.assistant.title")))
        .addComponent(createAssistantConfigurationForm())
        .addComponent(new TitledSeparator(
            CodeGPTBundle.get("configurationConfigurable.section.chatCompletion.title")))
        .addComponent(chatCompletionForm.createPanel())
        .addComponentFillVertically(new JPanel(), 0)
        .getPanel();
  }

  public JPanel getPanel() {
    return mainPanel;
  }

  public ConfigurationSettingsState getCurrentFormState() {
    var state = new ConfigurationSettingsState();
    state.setMaxTokens(maxTokensField.getValue());
    state.setTemperature(Float.parseFloat(temperatureField.getText()));
    state.setCheckForPluginUpdates(checkForPluginUpdatesCheckBox.isSelected());
    state.setCheckForNewScreenshots(checkForNewScreenshotsCheckBox.isSelected());
    state.setMethodNameGenerationEnabled(methodNameGenerationCheckBox.isSelected());
    state.setAutoFormattingEnabled(autoFormattingCheckBox.isSelected());
    state.setAutocompletionPostProcessingEnabled(autocompletionPostProcessingCheckBox.isSelected());
    state.setAutocompletionContextAwareEnabled(autocompletionContextAwareCheckBox.isSelected());
    state.setAutocompletionGitContextEnabled(autocompletionGitContextCheckBox.isSelected());
    state.setChatCompletionSettings(chatCompletionForm.getFormState());
    return state;
  }

  public void resetForm() {
    var configuration = ConfigurationSettings.getState();
    maxTokensField.setValue(configuration.getMaxTokens());
    temperatureField.setText(String.valueOf(configuration.getTemperature()));
    checkForPluginUpdatesCheckBox.setSelected(configuration.getCheckForPluginUpdates());
    checkForNewScreenshotsCheckBox.setSelected(configuration.getCheckForNewScreenshots());
    methodNameGenerationCheckBox.setSelected(configuration.getMethodNameGenerationEnabled());
    autoFormattingCheckBox.setSelected(configuration.getAutoFormattingEnabled());
    autocompletionPostProcessingCheckBox.setSelected(
        configuration.getAutocompletionPostProcessingEnabled());
    autocompletionContextAwareCheckBox.setSelected(
        configuration.getAutocompletionContextAwareEnabled());
    autocompletionGitContextCheckBox.setSelected(
        configuration.getAutocompletionGitContextEnabled()
    );
    chatCompletionForm.resetForm(configuration.getChatCompletionSettings());
  }

  // Formatted keys are not referenced in the messages bundle file
  private void addAssistantFormLabeledComponent(
      FormBuilder formBuilder,
      String labelKey,
      String commentKey,
      JComponent component) {
    formBuilder.addLabeledComponent(
        new JBLabel(CodeGPTBundle.get(labelKey))
            .withBorder(JBUI.Borders.emptyLeft(2)),
        UI.PanelFactory.panel(component)
            .resizeX(false)
            .withComment(CodeGPTBundle.get(commentKey))
            .withCommentHyperlinkListener(UIUtil::handleHyperlinkClicked)
            .createPanel(),
        true
    );
  }

  private JPanel createAssistantConfigurationForm() {
    var formBuilder = FormBuilder.createFormBuilder();
    addAssistantFormLabeledComponent(
        formBuilder,
        "configurationConfigurable.section.assistant.temperatureField.label",
        "configurationConfigurable.section.assistant.temperatureField.comment",
        temperatureField);
    addAssistantFormLabeledComponent(
        formBuilder,
        "configurationConfigurable.section.assistant.maxTokensField.label",
        "configurationConfigurable.section.assistant.maxTokensField.comment",
        maxTokensField);

    var form = formBuilder.getPanel();
    form.setBorder(JBUI.Borders.emptyLeft(16));
    return form;
  }

  private ComponentValidator createTemperatureInputValidator(
      Disposable parentDisposable,
      JBTextField component) {
    var validator = new ComponentValidator(parentDisposable)
        .withValidator(() -> {
          var valueText = component.getText();
          try {
            var value = Double.parseDouble(valueText);
            if (value > 1.0 || value < 0.0) {
              return new ValidationInfo(
                  CodeGPTBundle.get("validation.error.mustBeBetweenZeroAndOne"),
                  component);
            }
          } catch (NumberFormatException e) {
            return new ValidationInfo(
                CodeGPTBundle.get("validation.error.mustBeNumber"),
                component);
          }

          return null;
        })
        .andStartOnFocusLost()
        .installOn(component);
    validator.enableValidation();
    return validator;
  }
}
