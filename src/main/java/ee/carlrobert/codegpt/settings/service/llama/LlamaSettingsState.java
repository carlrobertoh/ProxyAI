package ee.carlrobert.codegpt.settings.service.llama;

import ee.carlrobert.codegpt.codecompletions.InfillPromptTemplate;
import ee.carlrobert.codegpt.completions.HuggingFaceModel;
import ee.carlrobert.codegpt.completions.llama.PromptTemplate;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Objects;

public class LlamaSettingsState {

  private boolean useCustomModel;
  private String customLlamaModelPath = "";
  private HuggingFaceModel huggingFaceModel = HuggingFaceModel.CODE_QWEN_2_5_1_5B_Q8_0;
  private PromptTemplate localModelPromptTemplate = PromptTemplate.LLAMA;
  private InfillPromptTemplate localModelInfillPromptTemplate = InfillPromptTemplate.CODE_LLAMA;
  private Integer serverPort = getRandomAvailablePortOrDefault();
  private int contextSize = 2048;
  private int threads = 8;
  private String additionalParameters = "";
  private String additionalBuildParameters = "";
  private String additionalEnvironmentVariables = "";
  private int topK = 40;
  private double topP = 0.9;
  private double minP = 0.05;
  private double repeatPenalty = 1.1;
  private boolean codeCompletionsEnabled = false;

  public boolean isUseCustomModel() {
    return useCustomModel;
  }

  public void setUseCustomModel(boolean useCustomModel) {
    this.useCustomModel = useCustomModel;
  }

  public String getCustomLlamaModelPath() {
    return customLlamaModelPath;
  }

  public void setCustomLlamaModelPath(String customLlamaModelPath) {
    this.customLlamaModelPath = customLlamaModelPath;
  }

  public HuggingFaceModel getHuggingFaceModel() {
    return huggingFaceModel;
  }

  public void setHuggingFaceModel(HuggingFaceModel huggingFaceModel) {
    this.huggingFaceModel = huggingFaceModel;
  }

  public PromptTemplate getLocalModelPromptTemplate() {
    return localModelPromptTemplate;
  }

  public void setLocalModelPromptTemplate(
      PromptTemplate localModelPromptTemplate) {
    this.localModelPromptTemplate = localModelPromptTemplate;
  }

  public InfillPromptTemplate getLocalModelInfillPromptTemplate() {
    return localModelInfillPromptTemplate;
  }

  public void setLocalModelInfillPromptTemplate(
      InfillPromptTemplate localModelInfillPromptTemplate) {
    this.localModelInfillPromptTemplate = localModelInfillPromptTemplate;
  }


  public Integer getServerPort() {
    return serverPort;
  }

  public void setServerPort(Integer serverPort) {
    this.serverPort = serverPort;
  }

  public int getContextSize() {
    return contextSize;
  }

  public void setContextSize(int contextSize) {
    this.contextSize = contextSize;
  }

  public int getThreads() {
    return threads;
  }

  public void setThreads(int threads) {
    this.threads = threads;
  }

  public String getAdditionalParameters() {
    return additionalParameters;
  }

  public void setAdditionalParameters(String additionalParameters) {
    this.additionalParameters = additionalParameters;
  }

  public String getAdditionalBuildParameters() {
    return additionalBuildParameters;
  }

  public void setAdditionalBuildParameters(String additionalBuildParameters) {
    this.additionalBuildParameters = additionalBuildParameters;
  }

  public String getAdditionalEnvironmentVariables() {
    return additionalEnvironmentVariables;
  }

  public void setAdditionalEnvironmentVariables(String additionalEnvironmentVariables) {
    this.additionalEnvironmentVariables = additionalEnvironmentVariables;
  }

  public int getTopK() {
    return topK;
  }

  public void setTopK(int topK) {
    this.topK = topK;
  }

  public double getTopP() {
    return topP;
  }

  public void setTopP(double topP) {
    this.topP = topP;
  }

  public double getMinP() {
    return minP;
  }

  public void setMinP(double minP) {
    this.minP = minP;
  }

  public double getRepeatPenalty() {
    return repeatPenalty;
  }

  public void setRepeatPenalty(double repeatPenalty) {
    this.repeatPenalty = repeatPenalty;
  }

  public boolean isCodeCompletionsEnabled() {
    return codeCompletionsEnabled;
  }

  public void setCodeCompletionsEnabled(boolean codeCompletionsEnabled) {
    this.codeCompletionsEnabled = codeCompletionsEnabled;
  }

  private static Integer getRandomAvailablePortOrDefault() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      return 8080;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    LlamaSettingsState that = (LlamaSettingsState) o;
    return useCustomModel == that.useCustomModel
        && contextSize == that.contextSize
        && threads == that.threads
        && topK == that.topK
        && Double.compare(that.topP, topP) == 0
        && Double.compare(that.minP, minP) == 0
        && Double.compare(that.repeatPenalty, repeatPenalty) == 0
        && Objects.equals(customLlamaModelPath, that.customLlamaModelPath)
        && huggingFaceModel == that.huggingFaceModel
        && localModelPromptTemplate == that.localModelPromptTemplate
        && localModelInfillPromptTemplate == that.localModelInfillPromptTemplate
        && Objects.equals(serverPort, that.serverPort)
        && Objects.equals(additionalParameters, that.additionalParameters)
        && Objects.equals(additionalBuildParameters, that.additionalBuildParameters)
        && Objects.equals(additionalEnvironmentVariables, that.additionalEnvironmentVariables)
        && codeCompletionsEnabled == that.codeCompletionsEnabled;
  }

  @Override
  public int hashCode() {
    return Objects.hash(useCustomModel, customLlamaModelPath, huggingFaceModel,
        localModelPromptTemplate, localModelInfillPromptTemplate,
        serverPort, contextSize, threads,
        additionalParameters, additionalBuildParameters, additionalEnvironmentVariables, topK, topP,
        minP, repeatPenalty,
        codeCompletionsEnabled);
  }
}
