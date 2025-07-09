package ee.carlrobert.codegpt.completions.llama;

import static java.lang.String.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.util.Key;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.CodeGPTPlugin;
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings;
import ee.carlrobert.codegpt.settings.service.llama.form.ServerProgressPanel;
import ee.carlrobert.codegpt.ui.OverlayUtil;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service
public final class LlamaServerAgent implements Disposable {

  private static final Logger LOG = Logger.getInstance(LlamaServerAgent.class);

  private @Nullable OSProcessHandler makeSetupProcessHandler;
  private @Nullable OSProcessHandler makeBuildProcessHandler;
  private @Nullable OSProcessHandler startServerProcessHandler;
  private ServerProgressPanel activeServerProgressPanel;
  private boolean stoppedByUser;

  public void startAgent(
      LlamaServerStartupParams params,
      ServerProgressPanel serverProgressPanel,
      Runnable onSuccess,
      Consumer<ServerProgressPanel> onServerStopped) {
    this.activeServerProgressPanel = serverProgressPanel;
    ApplicationManager.getApplication().invokeLater(() -> {
      try {
        stoppedByUser = false;
        serverProgressPanel.displayText(
            CodeGPTBundle.get("llamaServerAgent.buildingProject.description"));

        makeSetupProcessHandler = new OSProcessHandler(getCMakeSetupCommandLine(params));
        makeSetupProcessHandler.addProcessListener(new ProcessAdapter() {
          private final List<String> errorLines = new CopyOnWriteArrayList<>();

          @Override
          public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
            if (ProcessOutputType.isStderr(outputType)) {
              errorLines.add(event.getText());
              return;
            }
            LOG.info(event.getText());
          }

          @Override
          public void processTerminated(@NotNull ProcessEvent event) {
            int exitCode = event.getExitCode();
            LOG.info(format("CMake setup exited with code %d", exitCode));
            if (stoppedByUser) {
              onServerStopped.accept(activeServerProgressPanel);
              return;
            }
            if (exitCode != 0) {
              showServerError(String.join(",", errorLines), onServerStopped);
              return;
            }

            try {
              makeBuildProcessHandler = new OSProcessHandler(getCMakeBuildCommandLine(params));
              makeBuildProcessHandler.addProcessListener(
                  getMakeProcessListener(params, onSuccess, onServerStopped));
              makeBuildProcessHandler.startNotify();
            } catch (ExecutionException e) {
              showServerError(e.getMessage(), onServerStopped);
            }
          }
        });
        makeSetupProcessHandler.startNotify();
      } catch (ExecutionException e) {
        showServerError(e.getMessage(), onServerStopped);
      }
    });
  }

  public void stopAgent() {
    stoppedByUser = true;
    if (makeSetupProcessHandler != null) {
      makeSetupProcessHandler.destroyProcess();
    }
    if (startServerProcessHandler != null) {
      startServerProcessHandler.destroyProcess();
    }
  }

  public boolean isServerRunning() {
    return (makeSetupProcessHandler != null
        && makeSetupProcessHandler.isStartNotified()
        && !makeSetupProcessHandler.isProcessTerminated())
        || (startServerProcessHandler != null
        && startServerProcessHandler.isStartNotified()
        && !startServerProcessHandler.isProcessTerminated());
  }

  private ProcessListener getMakeProcessListener(
      LlamaServerStartupParams params,
      Runnable onSuccess,
      Consumer<ServerProgressPanel> onServerStopped) {
    LOG.info("Building llama project");

    return new ProcessAdapter() {

      private final List<String> errorLines = new CopyOnWriteArrayList<>();

      @Override
      public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
        if (ProcessOutputType.isStderr(outputType)) {
          errorLines.add(event.getText());
          return;
        }
        LOG.info(event.getText());
      }

      @Override
      public void processTerminated(@NotNull ProcessEvent event) {
        int exitCode = event.getExitCode();
        LOG.info(format("Server build exited with code %d", exitCode));
        if (stoppedByUser) {
          onServerStopped.accept(activeServerProgressPanel);
          return;
        }
        if (exitCode != 0) {
          showServerError(String.join(",", errorLines), onServerStopped);
          return;
        }

        try {
          LOG.info("Booting up llama server");

          activeServerProgressPanel.displayText(
              CodeGPTBundle.get("llamaServerAgent.serverBootup.description"));
          startServerProcessHandler = new OSProcessHandler.Silent(getServerCommandLine(params));
          startServerProcessHandler.addProcessListener(
              getProcessListener(params.port(), onSuccess, onServerStopped));
          startServerProcessHandler.startNotify();
        } catch (ExecutionException ex) {
          showServerError(ex.getMessage(), onServerStopped);
        }
      }
    };
  }

  private ProcessListener getProcessListener(
      int port,
      Runnable onSuccess,
      Consumer<ServerProgressPanel> onServerStopped) {
    return new ProcessAdapter() {
      private final ObjectMapper objectMapper = new ObjectMapper();
      private final List<String> errorLines = new CopyOnWriteArrayList<>();

      @Override
      public void processTerminated(@NotNull ProcessEvent event) {
        LOG.info(format("Server stopped with code %d", event.getExitCode()));
        if (stoppedByUser) {
          onServerStopped.accept(activeServerProgressPanel);
        } else {
          showServerError(String.join(",", errorLines), onServerStopped);
        }
      }

      @Override
      public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
        LOG.info(event.getText());

        // TODO: Use proper successful boot up validation
        if (event.getText().contains("server is listening")) {
          LOG.info("Server up and running!");

          LlamaSettings.getCurrentState().setServerPort(port);
          onSuccess.run();
        }
      }
    };
  }

  private void showServerError(String errorText, Consumer<ServerProgressPanel> onServerStopped) {
    onServerStopped.accept(activeServerProgressPanel);
    LOG.info("Unable to start llama server:\n" + errorText);
    OverlayUtil.showClosableBalloon(errorText, MessageType.ERROR, activeServerProgressPanel);
  }

  private static GeneralCommandLine getCMakeSetupCommandLine(LlamaServerStartupParams params) {
    GeneralCommandLine cmakeSetupCommand = new GeneralCommandLine().withCharset(
        StandardCharsets.UTF_8);
    cmakeSetupCommand.setExePath("cmake");
    cmakeSetupCommand.withWorkDirectory(CodeGPTPlugin.getLlamaSourcePath());
    cmakeSetupCommand.addParameters("-B", "build");
    cmakeSetupCommand.withEnvironment(params.additionalEnvironmentVariables());
    cmakeSetupCommand.setRedirectErrorStream(false);
    return cmakeSetupCommand;
  }

  private static GeneralCommandLine getCMakeBuildCommandLine(LlamaServerStartupParams params) {
    GeneralCommandLine cmakeBuildCommand = new GeneralCommandLine().withCharset(
        StandardCharsets.UTF_8);
    cmakeBuildCommand.setExePath("cmake");
    cmakeBuildCommand.withWorkDirectory(CodeGPTPlugin.getLlamaSourcePath());
    cmakeBuildCommand.addParameters("--build", "build", "--config", "Release", "-j", "4");
    cmakeBuildCommand.withEnvironment(params.additionalEnvironmentVariables());
    cmakeBuildCommand.setRedirectErrorStream(false);
    return cmakeBuildCommand;
  }

  private GeneralCommandLine getServerCommandLine(LlamaServerStartupParams params) {
    GeneralCommandLine commandLine = new GeneralCommandLine().withCharset(StandardCharsets.UTF_8);
    commandLine.setExePath("./build/bin/llama-server");
    commandLine.withWorkDirectory(CodeGPTPlugin.getLlamaSourcePath());
    commandLine.addParameters(
        "-m", params.modelPath(),
        "-c", String.valueOf(params.contextLength()),
        "--port", String.valueOf(params.port()),
        "-t", String.valueOf(params.threads()));
    commandLine.addParameters(params.additionalRunParameters());
    commandLine.withEnvironment(params.additionalEnvironmentVariables());
    commandLine.setRedirectErrorStream(false);
    return commandLine;
  }

  public void setActiveServerProgressPanel(
      ServerProgressPanel activeServerProgressPanel) {
    this.activeServerProgressPanel = activeServerProgressPanel;
  }

  @Override
  public void dispose() {
    if (makeSetupProcessHandler != null && !makeSetupProcessHandler.isProcessTerminated()) {
      makeSetupProcessHandler.destroyProcess();
    }
    if (startServerProcessHandler != null && !startServerProcessHandler.isProcessTerminated()) {
      startServerProcessHandler.destroyProcess();
    }
  }
}
