package ee.carlrobert.codegpt.toolwindow.chat.ui.textarea;

import static ee.carlrobert.codegpt.settings.service.ServiceType.ANTHROPIC;
import static ee.carlrobert.codegpt.settings.service.ServiceType.AZURE;
import static ee.carlrobert.codegpt.settings.service.ServiceType.CODEGPT;
import static ee.carlrobert.codegpt.settings.service.ServiceType.CUSTOM_OPENAI;
import static ee.carlrobert.codegpt.settings.service.ServiceType.GOOGLE;
import static ee.carlrobert.codegpt.settings.service.ServiceType.LLAMA_CPP;
import static ee.carlrobert.codegpt.settings.service.ServiceType.OLLAMA;
import static ee.carlrobert.codegpt.settings.service.ServiceType.OPENAI;
import static java.lang.String.format;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.CodeGPTKeys;
import ee.carlrobert.codegpt.Icons;
import ee.carlrobert.codegpt.completions.llama.LlamaModel;
import ee.carlrobert.codegpt.settings.GeneralSettings;
import ee.carlrobert.codegpt.settings.service.ProviderChangeNotifier;
import ee.carlrobert.codegpt.settings.service.ServiceType;
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTAvailableModels;
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTModel;
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings;
import ee.carlrobert.codegpt.settings.service.custom.CustomServiceSettingsState;
import ee.carlrobert.codegpt.settings.service.custom.CustomServicesSettings;
import ee.carlrobert.codegpt.settings.service.google.GoogleSettings;
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings;
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings;
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings;
import ee.carlrobert.codegpt.toolwindow.ui.CodeGPTModelsListPopupAction;
import ee.carlrobert.llm.client.google.models.GoogleModel;
import ee.carlrobert.llm.client.openai.completion.OpenAIChatCompletionModel;
import java.awt.Color;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.Icon;
import javax.swing.JComponent;
import org.jetbrains.annotations.NotNull;

public class ModelComboBoxAction extends ComboBoxAction {

  private static final Logger LOG = Logger.getInstance(ModelComboBoxAction.class);

  private final Consumer<ServiceType> onModelChange;
  private final Project project;
  private final List<ServiceType> availableProviders;

  public ModelComboBoxAction(
      Project project,
      Consumer<ServiceType> onModelChange,
      ServiceType selectedService) {
    this(project, onModelChange, selectedService, Arrays.asList(ServiceType.values()));
  }

  public ModelComboBoxAction(
      Project project,
      Consumer<ServiceType> onModelChange,
      ServiceType selectedProvider,
      List<ServiceType> availableProviders) {
    this.project = project;
    this.onModelChange = onModelChange;
    this.availableProviders = availableProviders;
    setSmallVariant(true);
    updateTemplatePresentation(selectedProvider);
    ApplicationManager.getApplication().getMessageBus()
        .connect()
        .subscribe(
            ProviderChangeNotifier.getTOPIC(),
            (ProviderChangeNotifier) this::updateTemplatePresentation);
  }

  public JComponent createCustomComponent(@NotNull String place) {
    return createCustomComponent(getTemplatePresentation(), place);
  }

  @NotNull
  @Override
  public JComponent createCustomComponent(
      @NotNull Presentation presentation,
      @NotNull String place) {
    ComboBoxButton button = createComboBoxButton(presentation);
    button.setForeground(
        EditorColorsManager.getInstance().getGlobalScheme().getDefaultForeground());
    button.setBorder(null);
    button.putClientProperty("JButton.backgroundColor", new Color(0, 0, 0, 0));
    return button;
  }

  private AnAction[] getCodeGPTModelActions(Project project, Presentation presentation) {
    var userDetails = CodeGPTKeys.CODEGPT_USER_DETAILS.get(project);
    return CodeGPTAvailableModels.getToolWindowModels(
            userDetails == null ? null : userDetails.getPricingPlan()).stream()
        .map(model -> createCodeGPTModelAction(model, presentation))
        .toArray(AnAction[]::new);
  }

  @Override
  protected @NotNull DefaultActionGroup createPopupActionGroup(JComponent button) {
    var presentation = ((ComboBoxButton) button).getPresentation();
    var actionGroup = new DefaultActionGroup();

    if (availableProviders.contains(CODEGPT)) {
      actionGroup.addSeparator(CodeGPTBundle.get("project.label"));
      actionGroup.addAll(getCodeGPTModelActions(project, presentation));
      actionGroup.addSeparator();
    }
    actionGroup.addSeparator("Cloud Providers");
    if (availableProviders.contains(OPENAI)) {
      var openaiGroup = DefaultActionGroup.createPopupGroup(() -> "OpenAI");
      openaiGroup.getTemplatePresentation().setIcon(Icons.OpenAI);
      List.of(
              OpenAIChatCompletionModel.O_3_MINI, OpenAIChatCompletionModel.O_1_PREVIEW,
              OpenAIChatCompletionModel.O_1_MINI,
              OpenAIChatCompletionModel.GPT_4_O,
              OpenAIChatCompletionModel.GPT_4_O_MINI,
              OpenAIChatCompletionModel.GPT_4_0125_128k)
          .forEach(model -> openaiGroup.add(createOpenAIModelAction(model, presentation)));
      actionGroup.add(openaiGroup);
    }
    if (availableProviders.contains(CUSTOM_OPENAI)) {
      List<CustomServiceSettingsState> services = ApplicationManager.getApplication()
          .getService(CustomServicesSettings.class)
          .getState()
          .getServices();

      var customGroup = DefaultActionGroup.createPopupGroup(() -> "Custom OpenAI");
      customGroup.getTemplatePresentation().setIcon(Icons.OpenAI);
      services.forEach(model ->
          customGroup.add(createCustomOpenAIModelAction(model, presentation))
      );
      actionGroup.add(customGroup);
    }
    if (availableProviders.contains(ANTHROPIC)) {
      actionGroup.add(createModelAction(
          ANTHROPIC,
          "Anthropic (Claude)",
          Icons.Anthropic,
          presentation));
    }
    if (availableProviders.contains(AZURE)) {
      actionGroup.add(
          createModelAction(AZURE, "Azure OpenAI", Icons.Azure, presentation));
    }
    if (availableProviders.contains(GOOGLE)) {
      var googleGroup = DefaultActionGroup.createPopupGroup(() -> "Google (Gemini)");
      googleGroup.getTemplatePresentation().setIcon(Icons.Google);
      List.of(
              GoogleModel.GEMINI_2_0_PRO_EXP,
              GoogleModel.GEMINI_2_0_FLASH_THINKING_EXP,
              GoogleModel.GEMINI_2_0_FLASH,
              GoogleModel.GEMINI_1_5_PRO)
          .forEach(model -> googleGroup.add(createGoogleModelAction(model, presentation)));
      actionGroup.add(googleGroup);
    }
    if (availableProviders.contains(LLAMA_CPP)) {
      actionGroup.addSeparator("Local Providers");
      actionGroup.add(createModelAction(
          LLAMA_CPP,
          getLlamaCppPresentationText(),
          Icons.Llama,
          presentation));
    }
    if (availableProviders.contains(OLLAMA)) {
      var ollamaGroup = DefaultActionGroup.createPopupGroup(() -> "Ollama");
      ollamaGroup.getTemplatePresentation().setIcon(Icons.Ollama);
      ApplicationManager.getApplication()
          .getService(OllamaSettings.class)
          .getState()
          .getAvailableModels()
          .forEach(model ->
              ollamaGroup.add(createOllamaModelAction(model, presentation)));
      actionGroup.add(ollamaGroup);
    }

    return actionGroup;
  }

  @Override
  protected boolean shouldShowDisabledActions() {
    return true;
  }

  private void updateTemplatePresentation(ServiceType selectedService) {
    var application = ApplicationManager.getApplication();
    var templatePresentation = getTemplatePresentation();
    switch (selectedService) {
      case CODEGPT:
        var modelCode = application.getService(CodeGPTServiceSettings.class)
            .getState()
            .getChatCompletionSettings()
            .getModel();
        var model = CodeGPTAvailableModels.getALL_CHAT_MODELS().stream()
            .filter(it -> it.getCode().equals(modelCode))
            .findFirst();
        templatePresentation.setIcon(model.map(CodeGPTModel::getIcon).orElse(Icons.CodeGPTModel));
        templatePresentation.setText(model.map(CodeGPTModel::getName).orElse("Unknown"));
        break;
      case OPENAI:
        templatePresentation.setIcon(Icons.OpenAI);

        var selectedModel = OpenAISettings.getCurrentState().getModel();
        try {
          templatePresentation.setText(
              OpenAIChatCompletionModel.findByCode(selectedModel).getDescription());
        } catch (Exception e) {
          LOG.error("Could find OpenAI model for code {}", e, selectedModel);
          // TODO: Find out why another provider's model was stored in the first place
          templatePresentation.setText(OpenAIChatCompletionModel.GPT_4_O.getDescription());
        }
        break;
      case CUSTOM_OPENAI:
        templatePresentation.setIcon(Icons.OpenAI);
        templatePresentation.setText(
            application.getService(CustomServicesSettings.class)
                .getState()
                .getActive()
                .getName());
        break;
      case ANTHROPIC:
        templatePresentation.setIcon(Icons.Anthropic);
        templatePresentation.setText("Anthropic (Claude)");
        break;
      case AZURE:
        templatePresentation.setIcon(Icons.Azure);
        templatePresentation.setText("Azure OpenAI");
        break;
      case LLAMA_CPP:
        templatePresentation.setText(getLlamaCppPresentationText());
        templatePresentation.setIcon(Icons.Llama);
        break;
      case OLLAMA:
        templatePresentation.setIcon(Icons.Ollama);
        templatePresentation.setText(application.getService(OllamaSettings.class)
            .getState()
            .getModel());
        break;
      case GOOGLE:
        templatePresentation.setText(getGooglePresentationText());
        templatePresentation.setIcon(Icons.Google);
        break;
      default:
        break;
    }
  }

  private String getGooglePresentationText() {
    var model = ApplicationManager.getApplication().getService(GoogleSettings.class)
        .getState()
        .getModel();
    var predefinedModel = GoogleModel.findByCode(model);
    if (predefinedModel == null) {
      return model;
    }
    return predefinedModel.getDescription();
  }

  private String getLlamaCppPresentationText() {
    var llamaSettingState = LlamaSettings.getCurrentState();
    if (!llamaSettingState.isRunLocalServer()) {
      return format("Remote %s", llamaSettingState.getRemoteModelPromptTemplate());
    }
    return getSelectedHuggingFace();
  }

  private String getSelectedHuggingFace() {
    var huggingFaceModel = LlamaSettings.getCurrentState().getHuggingFaceModel();
    var llamaModel = LlamaModel.findByHuggingFaceModel(huggingFaceModel);
    return format(
        "%s %dB (Q%d)",
        llamaModel.getLabel(),
        huggingFaceModel.getParameterSize(),
        huggingFaceModel.getQuantization());
  }

  private AnAction createModelAction(
      ServiceType serviceType,
      String label,
      Icon icon,
      Presentation comboBoxPresentation) {
    return createModelAction(serviceType, label, icon, comboBoxPresentation, null);
  }

  private AnAction createModelAction(
      ServiceType serviceType,
      String label,
      Icon icon,
      Presentation comboBoxPresentation,
      Runnable onModelChanged) {
    return new DumbAwareAction(label, "", icon) {
      @Override
      public void update(@NotNull AnActionEvent event) {
        var presentation = event.getPresentation();
        presentation.setEnabled(!presentation.getText().equals(comboBoxPresentation.getText()));
      }

      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        if (onModelChanged != null) {
          onModelChanged.run();
        }
        handleModelChange(serviceType);
      }

      @Override
      public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
      }
    };
  }

  private void handleModelChange(
      ServiceType serviceType) {
    GeneralSettings.getCurrentState().setSelectedService(serviceType);
    updateTemplatePresentation(serviceType);
    onModelChange.accept(serviceType);
  }

  private AnAction createCodeGPTModelAction(CodeGPTModel model, Presentation comboBoxPresentation) {
    return new CodeGPTModelsListPopupAction(model, comboBoxPresentation, () -> {
      ApplicationManager.getApplication()
          .getService(CodeGPTServiceSettings.class)
          .getState()
          .getChatCompletionSettings()
          .setModel(model.getCode());
      handleModelChange(CODEGPT);
    });
  }

  private AnAction createOllamaModelAction(String model, Presentation comboBoxPresentation) {
    return createModelAction(OLLAMA, model, Icons.Ollama, comboBoxPresentation,
        () -> ApplicationManager.getApplication()
            .getService(OllamaSettings.class)
            .getState()
            .setModel(model));
  }

  private AnAction createOpenAIModelAction(
      OpenAIChatCompletionModel model,
      Presentation comboBoxPresentation) {
    return createModelAction(OPENAI, model.getDescription(), Icons.OpenAI, comboBoxPresentation,
        () -> OpenAISettings.getCurrentState().setModel(model.getCode()));
  }

  private AnAction createCustomOpenAIModelAction(
      CustomServiceSettingsState model,
      Presentation comboBoxPresentation) {
    return createModelAction(CUSTOM_OPENAI, model.getName(), Icons.OpenAI, comboBoxPresentation,
        () -> ApplicationManager.getApplication()
            .getService(CustomServicesSettings.class)
            .getState()
            .setActive(model));
  }

  private AnAction createGoogleModelAction(GoogleModel model, Presentation comboBoxPresentation) {
    return createModelAction(GOOGLE, model.getDescription(), Icons.Google, comboBoxPresentation,
        () -> ApplicationManager.getApplication()
            .getService(GoogleSettings.class)
            .getState()
            .setModel(model.getCode()));
  }
}
