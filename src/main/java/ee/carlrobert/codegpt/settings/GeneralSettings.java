package ee.carlrobert.codegpt.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import ee.carlrobert.codegpt.completions.llama.LlamaModel;
import ee.carlrobert.codegpt.settings.service.ServiceType;
import ee.carlrobert.codegpt.settings.service.anthropic.AnthropicSettings;
import ee.carlrobert.codegpt.settings.service.codegpt.CodeGPTServiceSettings;
import ee.carlrobert.codegpt.settings.service.google.GoogleSettings;
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings;
import ee.carlrobert.codegpt.settings.service.ollama.OllamaSettings;
import ee.carlrobert.codegpt.settings.service.openai.OpenAISettings;
import org.jetbrains.annotations.NotNull;

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
    return getCurrentState().getSelectedService();
  }

  public static boolean isSelected(ServiceType serviceType) {
    return getSelectedService() == serviceType;
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
