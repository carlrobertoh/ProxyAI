package ee.carlrobert.codegpt.settings.service.openai;

import ee.carlrobert.llm.client.openai.completion.OpenAIChatCompletionModel;
import java.util.Objects;

public class OpenAISettingsState {

  private String organization = "";
  private String model = OpenAIChatCompletionModel.GPT_4_1.getCode();
  private boolean codeCompletionsEnabled = false;

  public String getOrganization() {
    return organization;
  }

  public void setOrganization(String organization) {
    this.organization = organization;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public boolean isCodeCompletionsEnabled() {
    return codeCompletionsEnabled;
  }

  public void setCodeCompletionsEnabled(boolean codeCompletionsEnabled) {
    this.codeCompletionsEnabled = codeCompletionsEnabled;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    OpenAISettingsState that = (OpenAISettingsState) o;
    return Objects.equals(organization, that.organization)
        && Objects.equals(model, that.model)
        && codeCompletionsEnabled == that.codeCompletionsEnabled;
  }

  @Override
  public int hashCode() {
    return Objects.hash(organization, model, codeCompletionsEnabled);
  }
}
