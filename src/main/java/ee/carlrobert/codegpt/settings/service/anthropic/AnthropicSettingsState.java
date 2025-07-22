package ee.carlrobert.codegpt.settings.service.anthropic;

import java.util.Objects;

public class AnthropicSettingsState {

  private String apiVersion = "2023-06-01";
  private String model = "claude-sonnet-4-20250514";
  private String baseHost = "";

  public String getApiVersion() {
    return apiVersion;
  }

  public void setApiVersion(String apiVersion) {
    this.apiVersion = apiVersion;
  }

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String getBaseHost() {
    return baseHost;
  }

  public void setBaseHost(String baseHost) {
    this.baseHost = baseHost;
  }

  public boolean hasCustomBaseHost() {
    return baseHost != null && !baseHost.trim().isEmpty();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    AnthropicSettingsState that = (AnthropicSettingsState) o;
    return Objects.equals(apiVersion, that.apiVersion)
            && Objects.equals(model, that.model)
            && Objects.equals(baseHost, that.baseHost);
  }

  @Override
  public int hashCode() {
    return Objects.hash(apiVersion, model, baseHost);
  }
}
