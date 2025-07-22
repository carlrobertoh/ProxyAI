package ee.carlrobert.codegpt.settings.service.llama;

import static ee.carlrobert.codegpt.settings.service.ServiceType.LLAMA_CPP;
import static org.apache.commons.lang3.SystemUtils.IS_OS_LINUX;
import static org.apache.commons.lang3.SystemUtils.IS_OS_MAC_OSX;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import ee.carlrobert.codegpt.codecompletions.InfillPromptTemplate;
import ee.carlrobert.codegpt.completions.HuggingFaceModel;
import ee.carlrobert.codegpt.completions.llama.LlamaModel;
import ee.carlrobert.codegpt.completions.llama.PromptTemplate;
import ee.carlrobert.codegpt.settings.service.FeatureType;
import ee.carlrobert.codegpt.settings.service.ModelSelectionService;
import ee.carlrobert.codegpt.settings.service.llama.form.LlamaSettingsForm;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

@State(name = "CodeGPT", storages = @Storage("CodeGPT_LlamaSettings.xml"))
public class LlamaSettings implements PersistentStateComponent<LlamaSettingsState> {

  private LlamaSettingsState state = new LlamaSettingsState();

  @Override
  @NotNull
  public LlamaSettingsState getState() {
    return state;
  }

  @Override
  public void loadState(@NotNull LlamaSettingsState state) {
    this.state = state;
    if (this.state.getHuggingFaceModel() == null) {
      this.state.setHuggingFaceModel(HuggingFaceModel.CODE_QWEN_2_5_1_5B_Q8_0);
    }
    if (this.state.getLocalModelPromptTemplate() == null) {
      this.state.setLocalModelPromptTemplate(PromptTemplate.CODE_QWEN);
    }
    if (this.state.getLocalModelInfillPromptTemplate() == null) {
      this.state.setLocalModelInfillPromptTemplate(InfillPromptTemplate.CODE_QWEN);
    }
  }

  public static LlamaSettingsState getCurrentState() {
    return getInstance().getState();
  }

  public static boolean isCodeCompletionsPossible() {
    return getInstance().getState().isCodeCompletionsEnabled()
        && LlamaModel.findByHuggingFaceModel(getInstance().getState().getHuggingFaceModel())
        .getInfillPromptTemplate() != null;
  }

  public static LlamaSettings getInstance() {
    return ApplicationManager.getApplication().getService(LlamaSettings.class);
  }

  public boolean isModified(LlamaSettingsForm form) {
    return !form.getCurrentState().equals(state);
  }

  public static boolean isRunnable() {
    return (IS_OS_MAC_OSX || IS_OS_LINUX)
        && ModelSelectionService.getInstance().getServiceForFeature(FeatureType.CHAT) == LLAMA_CPP;
  }

  public static boolean isRunnable(HuggingFaceModel model) {
    return isRunnable() && isModelExists(model);
  }

  public static boolean isModelExists(HuggingFaceModel model) {
    return getLlamaModelsPath().resolve(model.getFileName()).toFile().exists();
  }

  public static Path getLlamaModelsPath() {
    return Paths.get(System.getProperty("user.home"), ".codegpt/models/gguf");
  }

  public String getActualModelPath() {
    return state.isUseCustomModel()
        ? state.getCustomLlamaModelPath()
        : getLlamaModelsPath() + File.separator
            + state.getHuggingFaceModel().getFileName();
  }

  public static List<String> getAdditionalParametersList(String additionalParameters) {
    return Arrays.stream(additionalParameters.split(","))
        .map(String::trim)
        .filter(s -> !s.isBlank())
        .toList();
  }

  public static Map<String, String> getAdditionalEnvironmentVariablesMap(
      String additionalEnvironmentVariables) {
    return Arrays.stream(additionalEnvironmentVariables.split(" "))
        .map(String::trim)
        .filter(s -> !s.isBlank() && s.contains("="))
        .collect(Collectors.toMap(item -> item.split("=")[0].trim(),
            item -> item.split("=")[1].trim()));
  }

}
