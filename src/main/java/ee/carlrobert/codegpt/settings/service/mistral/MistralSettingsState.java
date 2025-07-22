package ee.carlrobert.codegpt.settings.service.mistral;

import java.util.Objects;

public class MistralSettingsState {

  private boolean codeCompletionsEnabled = true;

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
    MistralSettingsState that = (MistralSettingsState) o;
    return codeCompletionsEnabled == that.codeCompletionsEnabled;
  }

  @Override
  public int hashCode() {
    return Objects.hash(codeCompletionsEnabled);
  }
}