package ee.carlrobert.codegpt.settings;

import com.intellij.openapi.options.Configurable;
import ee.carlrobert.codegpt.CodeGPTBundle;
import javax.swing.JComponent;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

public class GeneralSettingsConfigurable implements Configurable {

  private GeneralSettingsComponent component;

  @Override
  public String getDisplayName() {
    return CodeGPTBundle.get("settings.displayName");
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    return component.getPreferredFocusedComponent();
  }

  @Nullable
  @Override
  public JComponent createComponent() {
    component = new GeneralSettingsComponent(GeneralSettings.getInstance());
    return component.getPanel();
  }

  @Override
  public boolean isModified() {
    var settings = GeneralSettings.getCurrentState();
    return !component.getDisplayName().equals(settings.getDisplayName());
  }

  @Override
  public void apply() {
    GeneralSettings.getCurrentState().setDisplayName(component.getDisplayName());
  }

  @Override
  public void reset() {
    component.setDisplayName(GeneralSettings.getCurrentState().getDisplayName());
  }
}
