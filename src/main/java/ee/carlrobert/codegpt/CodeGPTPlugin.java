package ee.carlrobert.codegpt;

import static java.io.File.separator;
import static java.util.Objects.requireNonNull;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManager;
import com.intellij.openapi.extensions.PluginId;
import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;

public final class CodeGPTPlugin {

  public static final PluginId CODEGPT_ID = PluginId.getId("ee.carlrobert.chatgpt");

  private CodeGPTPlugin() {
  }

  private static @NotNull IdeaPluginDescriptor getDescriptor() {
    return requireNonNull(PluginManager.getPlugin(CODEGPT_ID));
  }

  public static @NotNull String getVersion() {
    return getDescriptor().getVersion();
  }

  public static @NotNull Path getPluginBasePath() {
    return getDescriptor().getPluginPath();
  }

  public static @NotNull String getLlamaSourcePath() {
    return getPluginBasePath() + separator + "llama.cpp";
  }
}
