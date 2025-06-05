package ee.carlrobert.codegpt.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import ee.carlrobert.codegpt.CodeGPTKeys;
import ee.carlrobert.codegpt.completions.HuggingFaceModel;
import ee.carlrobert.codegpt.completions.llama.LlamaModel;
import ee.carlrobert.codegpt.conversations.Conversation;
import ee.carlrobert.codegpt.settings.service.ModelRole;
import ee.carlrobert.codegpt.settings.service.ProviderChangeNotifier;
import ee.carlrobert.codegpt.settings.service.ServiceType;
import ee.carlrobert.codegpt.settings.service.anthropic.AnthropicSettings;
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTService;
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings;
import ee.carlrobert.codegpt.settings.service.google.GoogleSettings;
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings;
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings;
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings;
import ee.carlrobert.codegpt.util.ApplicationUtil;
import org.jetbrains.annotations.NotNull;

import static ee.carlrobert.codegpt.settings.service.ModelRole.CHAT_ROLE;

@State(name = "CodeGPT_GeneralSettings_270", storages = @Storage("CodeGPT_GeneralSettings_270.xml"))
public class GeneralSettings implements PersistentStateComponent<GeneralSettingsState> {

  private GeneralSettingsState state = new GeneralSettingsState();

  @Override
  @NotNull
  public GeneralSettingsState getState() {
    return state;
  }

  @Override
  public void loadState(@NotNull GeneralSettingsState state) {
    this.state = state;
  }

  public static GeneralSettingsState getCurrentState() {
    return getInstance().getState();
  }

  public static GeneralSettings getInstance() {
    return ApplicationManager.getApplication().getService(GeneralSettings.class);
  }

  public static ServiceType getSelectedService() {
    return getCurrentState().getSelectedService(CHAT_ROLE);
  }

  public static ServiceType getSelectedService(ModelRole role) {
    return getCurrentState().getSelectedService(role);
  }

  public static boolean isSelected(ServiceType serviceType) {
    return getSelectedService() == serviceType;
  }

  public static boolean isSelected(ModelRole role, ServiceType serviceType) {
    return getSelectedService(role) == serviceType;
  }

  public void sync(Conversation conversation) {
    var project = ApplicationUtil.findCurrentProject();
    var provider = ServiceType.fromClientCode(conversation.getClientCode());
    switch (provider) {
      case OPENAI:
        OpenAISettings.getCurrentState().setModel(conversation.getModel());
        break;
      case LLAMA_CPP:
        var llamaSettings = LlamaSettings.getCurrentState();
        try {
          var huggingFaceModel = HuggingFaceModel.valueOf(conversation.getModel());
          llamaSettings.setHuggingFaceModel(huggingFaceModel);
          llamaSettings.setUseCustomModel(false);
        } catch (IllegalArgumentException ignore) {
          llamaSettings.setCustomLlamaModelPath(conversation.getModel());
          llamaSettings.setUseCustomModel(true);
        }
        break;
      case CODEGPT:
        ApplicationManager.getApplication().getService(CodeGPTServiceSettings.class).getState()
            .getChatCompletionSettings().setModel(conversation.getModel());

        var existingUserDetails = CodeGPTKeys.CODEGPT_USER_DETAILS.get(project);
        if (project != null && existingUserDetails == null) {
          project.getService(CodeGPTService.class).syncUserDetailsAsync();
        }
        break;
      case ANTHROPIC:
        ApplicationManager.getApplication().getService(AnthropicSettings.class).getState()
            .setModel(conversation.getModel());
        break;
      case GOOGLE:
        ApplicationManager.getApplication().getService(GoogleSettings.class).getState()
            .setModel(conversation.getModel());
        break;
      case OLLAMA:
        ApplicationManager.getApplication().getService(OllamaSettings.class).getState()
            .setModel(conversation.getModel());
        break;
      default:
        break;
    }
    state.setSelectedService(CHAT_ROLE, provider);
    if (project != null) {
      project.getMessageBus()
          .syncPublisher(ProviderChangeNotifier.getTOPIC())
          .providerChanged(provider);
    }
  }

  public String getModel() {
    switch (state.getSelectedService()) {
      case CODEGPT:
        return ApplicationManager.getApplication().getService(CodeGPTServiceSettings.class)
            .getState()
            .getCodeCompletionSettings()
            .getModel();
      case OPENAI:
        return OpenAISettings.getCurrentState().getModel();
      case ANTHROPIC:
        return AnthropicSettings.getCurrentState().getModel();
      case LLAMA_CPP:
        var llamaSettings = LlamaSettings.getCurrentState();
        if (llamaSettings.isUseCustomModel()) {
          var filePath = llamaSettings.getCustomLlamaModelPath();
          int lastSeparatorIndex = filePath.lastIndexOf('/');
          if (lastSeparatorIndex == -1) {
            return filePath;
          }
          return filePath.substring(lastSeparatorIndex + 1);
        }
        var huggingFaceModel = llamaSettings.getHuggingFaceModel();
        var llamaModel = LlamaModel.findByHuggingFaceModel(huggingFaceModel);
        return String.format(
            "%s %s %dB (Q%d)",
            llamaModel.getDownloadedMarker(),
            llamaModel.getLabel(),
            huggingFaceModel.getParameterSize(),
            huggingFaceModel.getQuantization());
      case OLLAMA:
        return ApplicationManager.getApplication()
            .getService(OllamaSettings.class)
            .getState()
            .getModel();
      case GOOGLE:
        return ApplicationManager.getApplication()
            .getService(GoogleSettings.class)
            .getState()
            .getModel();
      default:
        return "Unknown";
    }
  }
}
