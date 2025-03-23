/*******************************************************************************
 * Copyright (c) 2021 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package ee.carlrobert.codegpt.telemetry.ui.preferences;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.SearchableConfigurable;
import ee.carlrobert.codegpt.telemetry.core.configuration.TelemetryConfiguration;
import java.io.IOException;
import javax.swing.JComponent;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Controller for telemetry settings.
 */
public class TelemetryConfigurable implements SearchableConfigurable {

  /* plugin.xml > applicationConfigurable > id */
  public static final String ID = "tools.preferences.carlrobert.telemetry";

  private static final Logger LOGGER = Logger.getInstance(TelemetryConfigurable.class);

  private TelemetryComponent component;
  private final TelemetryConfiguration configuration = TelemetryConfiguration.getInstance();

  @Nls(capitalization = Nls.Capitalization.Title)
  @Override
  public String getDisplayName() {
    return "ProxyAI Telemetry";
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return component.getPreferredFocusedComponent();
  }

  @Nullable
  @Override
  public JComponent createComponent() {
    this.component = new TelemetryComponent();
    return component.getPanel();
  }

  @Override
  public boolean isModified() {
      boolean modified = false;
      modified |= (component.getUsageStatisticsEnabled() != configuration.isEnabled());
      modified |= (component.getCompletionStatisticsEnabled() != configuration.isCompletionTelemetryEnabled());
      return modified;
  }

  @Override
  public void apply() {
    configuration.setEnabled(component.getUsageStatisticsEnabled());
    configuration.setCompletionTelemetryEnabled(component.getCompletionStatisticsEnabled());
    try {
      configuration.save();
    } catch (IOException e) {
      LOGGER.warn("Could not save telemetry configuration.", e);
    }
  }

  @Override
  public void reset() {
    component.setUsageStatisticsEnabled(configuration.isEnabled());
    component.setCompletionStatisticsEnabled(configuration.isCompletionTelemetryEnabled());
  }

  @Override
  public void disposeUIResources() {
    component = null;
  }

  @NotNull
  @Override
  public String getId() {
    return ID;
  }
}
