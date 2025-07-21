package ee.carlrobert.codegpt.settings.service.mistral;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

@State(name = "CodeGPT_MistralSettings", storages = @Storage("CodeGPT_MistralSettings.xml"))
public class MistralSettings implements PersistentStateComponent<MistralSettingsState> {

  private MistralSettingsState state = new MistralSettingsState();

  @Override
  @NotNull
  public MistralSettingsState getState() {
    return state;
  }

  @Override
  public void loadState(@NotNull MistralSettingsState state) {
    this.state = state;
  }

  public static MistralSettingsState getCurrentState() {
    return getInstance().getState();
  }

  public static MistralSettings getInstance() {
    return ApplicationManager.getApplication().getService(MistralSettings.class);
  }
}