package ee.carlrobert.codegpt.telemetry;

import ee.carlrobert.codegpt.telemetry.core.service.TelemetryMessageBuilder.ActionMessage;

public enum TelemetryAction {

  COMPLETION("CodeGPT-Completion"),
  COMPLETION_ERROR("CodeGPT-Completion-Error"),
  IDE_ACTION("CodeGPT-Action"),
  IDE_ACTION_ERROR("CodeGPT-Action-Error"),
  SETTINGS_CHANGED("CodeGPT-Settings-Changed");

  private final String code;

  TelemetryAction(String code) {
    this.code = code;
  }

  public String getCode() {
    return code;
  }

  public ActionMessage createActionMessage() {
    return TelemetryMessageProvider.builder().action(getCode());
  }
}
