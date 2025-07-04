package ee.carlrobert.codegpt.settings.advanced;

import com.intellij.openapi.options.Configurable;
import ee.carlrobert.codegpt.CodeGPTBundle;
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
    return !component.getCurrentFormState().equals(AdvancedSettings.getCurrentState());
  }

  @Override
  public void apply() {
    AdvancedSettings.getInstance().loadState(component.getCurrentFormState());
  }

  @Override
  public void reset() {
    component.resetForm();
  }

  @Override
  public void disposeUIResources() {
    component = null;
  }
}
