package ee.carlrobert.codegpt.settings.advanced;

import com.intellij.openapi.options.Configurable;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings;
import javax.swing.JComponent;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

public class AdvancedSettingsConfigurable implements Configurable {

  private AdvancedSettingsComponent component;

  @Override
  public String getDisplayName() {
    return CodeGPTBundle.get("advancedSettingsConfigurable.displayName");
  }

  @Nullable
  @Override
  public JComponent createComponent() {
    component = new AdvancedSettingsComponent(AdvancedSettings.getCurrentState());
    return component.getPanel();
  }

  @Override
  public boolean isModified() {
    boolean advChanged = !component.getCurrentFormState().equals(AdvancedSettings.getCurrentState());
    boolean debugChanged = component.isDebugModeEnabled() !=
        ConfigurationSettings.getState().getDebugModeEnabled();
    return advChanged || debugChanged;
  }

  @Override
  public void apply() {
    AdvancedSettings.getInstance().loadState(component.getCurrentFormState());
    ConfigurationSettings.getState().setDebugModeEnabled(component.isDebugModeEnabled());
  }

  @Override
  public void reset() {
    component.resetForm();
    component.setDebugModeEnabled(ConfigurationSettings.getState().getDebugModeEnabled());
  }

  @Override
  public void disposeUIResources() {
    component = null;
  }
}
