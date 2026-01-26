package ee.carlrobert.codegpt.telemetry;

import ee.carlrobert.codegpt.telemetry.core.service.TelemetryMessageBuilder;
import ee.carlrobert.codegpt.telemetry.core.util.Lazy;

public class TelemetryMessageProvider {

  private static final TelemetryMessageProvider INSTANCE = new TelemetryMessageProvider();

  private final Lazy<TelemetryMessageBuilder> builder = new Lazy<>(() ->
      new TelemetryMessageBuilder(TelemetryMessageProvider.class.getClassLoader()));

  public static TelemetryMessageBuilder builder() {
    return INSTANCE.builder.get();
  }
}