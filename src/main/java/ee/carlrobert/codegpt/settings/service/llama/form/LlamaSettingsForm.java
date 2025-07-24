package ee.carlrobert.codegpt.settings.service.llama.form;

import static ee.carlrobert.codegpt.ui.UIUtil.createComment;
import static ee.carlrobert.codegpt.ui.UIUtil.withEmptyLeftBorder;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.JBColor;
import com.intellij.ui.PortField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.fields.IntegerField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.AsyncProcessIcon;
import com.intellij.util.ui.components.BorderLayoutPanel;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.completions.llama.LlamaServerAgent;
import ee.carlrobert.codegpt.completions.llama.LlamaServerStartupParams;
import ee.carlrobert.codegpt.completions.llama.SimpleConsolePanel;
import ee.carlrobert.codegpt.completions.llama.logging.SettingsFormLoggingStrategy;
import ee.carlrobert.codegpt.services.llama.ServerLogsManager;
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettings;
import ee.carlrobert.codegpt.settings.service.llama.LlamaSettingsState;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.NotNull;

public class LlamaSettingsForm extends JPanel {

  private static final int SERVER_CONFIG_TAB = 0;
  private static final int SERVER_LOGS_TAB = 1;
  private static final int BUILD_OUTPUT_TAB = 2;
  private static final String SERVER_LOGS_DISABLED_TOOLTIP = "Server must be running to view logs";
  private static final String BUILD_OUTPUT_DISABLED_TOOLTIP = "Available during build process";

  private final LlamaServerAgent serverAgent;
  private final LlamaSettingsState settingsState;

  private final JBTabbedPane tabbedPane;
  private final LlamaServerPreferencesForm serverPreferencesForm;
  private final SimpleConsolePanel serverLogsConsole;
  private final SimpleConsolePanel buildLogsConsole;
  private final JPanel serverStatusPanel;
  private final JBLabel serverStatusLabel;
  private final AsyncProcessIcon serverStatusSpinner;

  public LlamaSettingsForm(LlamaSettingsState settingsState) {
    this.settingsState = settingsState;
    serverAgent = ApplicationManager.getApplication().getService(LlamaServerAgent.class);
    tabbedPane = new JBTabbedPane();
    serverStatusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    serverStatusLabel = new JBLabel();
    serverStatusSpinner = new AsyncProcessIcon("server_status_spinner");
    serverStatusPanel.add(serverStatusSpinner);
    serverStatusPanel.add(Box.createHorizontalStrut(4));
    serverStatusPanel.add(serverStatusLabel);
    serverStatusSpinner.setVisible(false);
    serverPreferencesForm = new LlamaServerPreferencesForm(settingsState, this);
    serverLogsConsole = new SimpleConsolePanel();
    buildLogsConsole = new SimpleConsolePanel();

    updateServerStatus();
    loadExistingLogs();

    setLayout(new BorderLayout());
    add(createLocalServerPanel(), BorderLayout.CENTER);

    serverAgent.setSettingsForm(this);
  }

  private JPanel createLocalServerPanel() {
    var panel = new BorderLayoutPanel();
    var modelPreferencesForm = serverPreferencesForm.getLlamaModelPreferencesForm();
    var modelPanel = new BorderLayoutPanel();
    modelPanel.add(modelPreferencesForm.getForm(), BorderLayout.CENTER);
    modelPanel.setBorder(JBUI.Borders.emptyBottom(8));
    panel.add(modelPanel, BorderLayout.NORTH);

    var serverConfigTab = createServerConfigurationTab();
    tabbedPane.addTab(CodeGPTBundle.get("llama.ui.tab.serverConfiguration"), AllIcons.General.Settings, serverConfigTab);

    var serverLogsTab = createServerLogsTab();
    tabbedPane.addTab(CodeGPTBundle.get("llama.ui.tab.serverLogs"), AllIcons.Debugger.Console, serverLogsTab);

    var buildLogsTab = createBuildLogsTab();
    tabbedPane.addTab(CodeGPTBundle.get("llama.ui.tab.buildOutput"), AllIcons.Actions.Compile, buildLogsTab);

    tabbedPane.addChangeListener(e -> {
      SwingUtilities.invokeLater(() -> {
        int selectedIndex = tabbedPane.getSelectedIndex();
        if (selectedIndex == 1) {
          serverLogsConsole.revalidate();
          serverLogsConsole.repaint();
        } else if (selectedIndex == 2) {
          buildLogsConsole.revalidate();
          buildLogsConsole.repaint();
        }
      });
    });

    panel.add(tabbedPane, BorderLayout.CENTER);

    setTabState(SERVER_LOGS_TAB, serverAgent.isServerRunning(), SERVER_LOGS_DISABLED_TOOLTIP);
    setTabState(BUILD_OUTPUT_TAB, serverAgent.isBuildInProgress(), BUILD_OUTPUT_DISABLED_TOOLTIP);

    return panel;
  }

  private JPanel createServerConfigurationTab() {
    var serverRunning = serverAgent.isServerRunning();

    var portField = new PortField(serverPreferencesForm.getServerPort());
    portField.setEnabled(!serverRunning);

    var contextSizeField = new IntegerField("context_size", 256, 4096);
    contextSizeField.setColumns(12);
    contextSizeField.setValue(serverPreferencesForm.getContextSize());
    contextSizeField.setEnabled(!serverRunning);

    var threadsField = new IntegerField("threads", 1, 256);
    threadsField.setColumns(12);
    threadsField.setValue(serverPreferencesForm.getThreads());
    threadsField.setEnabled(!serverRunning);

    var additionalParametersField = new JBTextField(serverPreferencesForm.getAdditionalParameters(),
        30);
    additionalParametersField.setEnabled(!serverRunning);

    var additionalBuildParametersField = new JBTextField(
        serverPreferencesForm.getAdditionalBuildParameters(), 30);
    additionalBuildParametersField.setEnabled(!serverRunning);

    var additionalEnvironmentVariablesField = new JBTextField(
        serverPreferencesForm.getAdditionalEnvironmentVariables(), 30);
    additionalEnvironmentVariablesField.setEnabled(!serverRunning);

    var config = new ServerButtonConfig(
        portField,
        contextSizeField,
        threadsField,
        additionalParametersField,
        additionalBuildParametersField,
        additionalEnvironmentVariablesField
    );

    var serverButton = createServerButton(config);

    var portPanel = new JPanel();
    portPanel.setLayout(new BoxLayout(portPanel, BoxLayout.X_AXIS));
    portPanel.add(portField);
    portPanel.add(Box.createHorizontalGlue());
    portPanel.add(serverButton);

    var form = withEmptyLeftBorder(FormBuilder.createFormBuilder()
        .addVerticalGap(8)
        .addLabeledComponent(CodeGPTBundle.get("shared.port"), portPanel)
        .addVerticalGap(8)
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.service.llama.contextSize.label"),
            contextSizeField)
        .addComponentToRightColumn(
            createComment("settingsConfigurable.service.llama.contextSize.comment"))
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.service.llama.threads.label"),
            threadsField)
        .addComponentToRightColumn(
            createComment("settingsConfigurable.service.llama.threads.comment"))
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.service.llama.additionalParameters.label"),
            additionalParametersField)
        .addComponentToRightColumn(
            createComment("settingsConfigurable.service.llama.additionalParameters.comment"))
        .addLabeledComponent(
            CodeGPTBundle.get("settingsConfigurable.service.llama.additionalBuildParameters.label"),
            additionalBuildParametersField)
        .addComponentToRightColumn(
            createComment("settingsConfigurable.service.llama.additionalBuildParameters.comment"))
        .addLabeledComponent(
            CodeGPTBundle.get(
                "settingsConfigurable.service.llama.additionalEnvironmentVariables.label"),
            additionalEnvironmentVariablesField)
        .addComponentToRightColumn(
            createComment(
                "settingsConfigurable.service.llama.additionalEnvironmentVariables.comment"))
        .addComponentFillVertically(new JPanel(), 0)
        .getPanel());

    var panel = new JPanel(new BorderLayout());
    panel.add(form, BorderLayout.CENTER);

    return panel;
  }

  private JButton createServerButton(ServerButtonConfig config) {
    var serverRunning = serverAgent.isServerRunning();
    var buildInProgress = serverAgent.isBuildInProgress();
    var serverButton = new JButton();

    if (serverRunning) {
      serverButton.setText(
          CodeGPTBundle.get("settingsConfigurable.service.llama.stopServer.label"));
      serverButton.setIcon(AllIcons.Actions.Suspend);
    } else if (buildInProgress) {
      serverButton.setText(CodeGPTBundle.get("llama.ui.button.stopBuild"));
      serverButton.setIcon(AllIcons.Actions.Suspend);
    } else {
      serverButton.setText(
          CodeGPTBundle.get("settingsConfigurable.service.llama.startServer.label"));
      serverButton.setIcon(AllIcons.Actions.Execute);
    }

    serverButton.addActionListener(e -> {
      if (serverAgent.isServerRunning()) {
        serverAgent.stopAgent();
        setFieldsEnabled(true, config);
        serverButton.setText(
            CodeGPTBundle.get("settingsConfigurable.service.llama.startServer.label"));
        serverButton.setIcon(AllIcons.Actions.Execute);
      } else if (serverAgent.isBuildInProgress()) {
        serverAgent.stopAgent();
        setFieldsEnabled(true, config);
        serverButton.setText(
            CodeGPTBundle.get("settingsConfigurable.service.llama.startServer.label"));
        serverButton.setIcon(AllIcons.Actions.Execute);
      } else {
        setTabState(BUILD_OUTPUT_TAB, true, BUILD_OUTPUT_DISABLED_TOOLTIP);
        tabbedPane.setSelectedIndex(BUILD_OUTPUT_TAB);
        serverButton.setText(CodeGPTBundle.get("llama.ui.button.stopBuild"));
        serverButton.setIcon(AllIcons.Actions.Suspend);
        setFieldsEnabled(false, config);

        logToConsole(CodeGPTBundle.get("llama.process.startingBuild"), false, true);

        var params = new LlamaServerStartupParams(
            serverPreferencesForm.getLlamaModelPreferencesForm().getActualModelPath(),
            config.getContextSizeField().getValue(),
            config.getThreadsField().getValue(),
            config.getPortField().getNumber(),
            LlamaSettings.getAdditionalParametersList(
                config.getAdditionalParametersField().getText()),
            LlamaSettings.getAdditionalParametersList(
                config.getAdditionalBuildParametersField().getText()),
            LlamaSettings.getAdditionalEnvironmentVariablesMap(
                config.getAdditionalEnvironmentVariablesField().getText())
        );

        serverAgent.startAgent(
            params,
            new SettingsFormLoggingStrategy(this),
            () -> {
              SwingUtilities.invokeLater(() -> {
                serverButton.setText(
                    CodeGPTBundle.get("settingsConfigurable.service.llama.stopServer.label"));
                serverButton.setIcon(AllIcons.Actions.Suspend);
                setTabState(SERVER_LOGS_TAB, true, SERVER_LOGS_DISABLED_TOOLTIP);
                setTabState(BUILD_OUTPUT_TAB, false, BUILD_OUTPUT_DISABLED_TOOLTIP);
                tabbedPane.setSelectedIndex(SERVER_LOGS_TAB);
              });
            },
            () -> {
              SwingUtilities.invokeLater(() -> {
                setFieldsEnabled(true, config);
                serverButton.setText(
                    CodeGPTBundle.get("settingsConfigurable.service.llama.startServer.label"));
                serverButton.setIcon(AllIcons.Actions.Execute);
              });
            }
        );
      }
    });

    return serverButton;
  }

  private void setFieldsEnabled(boolean enabled, ServerButtonConfig config) {
    config.getPortField().setEnabled(enabled);
    config.getContextSizeField().setEnabled(enabled);
    config.getThreadsField().setEnabled(enabled);
    config.getAdditionalParametersField().setEnabled(enabled);
    config.getAdditionalBuildParametersField().setEnabled(enabled);
    config.getAdditionalEnvironmentVariablesField().setEnabled(enabled);
  }

  private JPanel createBuildLogsTab() {
    return createLogsTab(buildLogsConsole, false);
  }

  private JPanel createServerLogsTab() {
    return createLogsTab(serverLogsConsole, true);
  }

  private JPanel createLogsTab(Component view, boolean isServerLogs) {
    var panel = new JPanel(new BorderLayout());
    panel.add(createLogsToolbar(isServerLogs), BorderLayout.NORTH);

    var scrollPane = new JScrollPane(view);
    scrollPane.setPreferredSize(JBUI.size(600, 240));
    panel.add(scrollPane, BorderLayout.CENTER);
    panel.add(createStatusBar(), BorderLayout.SOUTH);

    return panel;
  }

  private JComponent createLogsToolbar(boolean isServerLogs) {
    var actionGroup = new DefaultActionGroup();

    actionGroup.add(new DumbAwareAction(CodeGPTBundle.get("llama.ui.action.clear"), CodeGPTBundle.get("llama.ui.action.clear.description"), AllIcons.Actions.GC) {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        var consolePanel = isServerLogs ? serverLogsConsole : buildLogsConsole;
        consolePanel.clearConsole();
      }
    });

    actionGroup.add(new DumbAwareAction(CodeGPTBundle.get("llama.ui.action.scrollToEnd"), CodeGPTBundle.get("llama.ui.action.scrollToEnd.description"),
        AllIcons.RunConfigurations.Scroll_down) {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        var consolePanel = isServerLogs ? serverLogsConsole : buildLogsConsole;
        consolePanel.setCaretPosition(consolePanel.getDocument().getLength());
      }
    });

    var toolbar = ActionManager.getInstance().createActionToolbar("LlamaLogs", actionGroup, true);
    toolbar.setTargetComponent(this);
    return toolbar.getComponent();
  }

  private JPanel createStatusBar() {
    var statusBar = new JPanel(new BorderLayout());
    statusBar.setBorder(JBUI.Borders.compound(
        JBUI.Borders.customLine(JBUI.CurrentTheme.ToolWindow.borderColor(), 1, 0, 0, 0),
        JBUI.Borders.empty(4)
    ));

    statusBar.add(serverStatusPanel, BorderLayout.WEST);
    return statusBar;
  }

  private void loadExistingLogs() {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      var logsManager = ApplicationManager.getApplication().getService(ServerLogsManager.class);
      var logs = logsManager.getSessionLogs(logsManager.getCurrentSession().getId());

      SwingUtilities.invokeLater(() -> {
        for (var log : logs) {
          boolean isError = log.getMessage().toLowerCase().contains("error") ||
              log.getMessage().toLowerCase().contains("exception") ||
              log.getMessage().toLowerCase().contains("failed");
          serverLogsConsole.appendText(log.getMessage(), isError);
        }
      });
    });
  }

  private void updateServerStatus() {
    SwingUtilities.invokeLater(() -> {
      if (serverAgent.isServerRunning()) {
        serverStatusLabel.setText(CodeGPTBundle.get("llama.ui.status.running"));
        serverStatusLabel.setIcon(AllIcons.General.InspectionsOK);
        serverStatusLabel.setForeground(JBColor.GREEN);
        serverStatusSpinner.setVisible(false);
        setTabState(SERVER_LOGS_TAB, true, SERVER_LOGS_DISABLED_TOOLTIP);
        setTabState(BUILD_OUTPUT_TAB, false, BUILD_OUTPUT_DISABLED_TOOLTIP);
      } else if (serverAgent.isBuildInProgress()) {
        serverStatusLabel.setText(CodeGPTBundle.get("llama.ui.status.building"));
        serverStatusLabel.setIcon(null);
        serverStatusLabel.setForeground(JBColor.BLUE);
        serverStatusSpinner.setVisible(true);
        setTabState(SERVER_LOGS_TAB, false, SERVER_LOGS_DISABLED_TOOLTIP);
        setTabState(BUILD_OUTPUT_TAB, true, BUILD_OUTPUT_DISABLED_TOOLTIP);
      } else {
        serverStatusLabel.setText(CodeGPTBundle.get("llama.ui.status.stopped"));
        serverStatusLabel.setIcon(AllIcons.General.InspectionsEye);
        serverStatusLabel.setForeground(JBColor.GRAY);
        serverStatusSpinner.setVisible(false);
        setTabState(SERVER_LOGS_TAB, false, SERVER_LOGS_DISABLED_TOOLTIP);
        setTabState(BUILD_OUTPUT_TAB, false, BUILD_OUTPUT_DISABLED_TOOLTIP);
      }
    });
  }

  public void refreshServerStatus() {
    updateServerStatus();
  }

  public void updateServerStatusWithPhase(String phase) {
    SwingUtilities.invokeLater(() -> {
      serverStatusLabel.setText(phase);
      serverStatusLabel.setIcon(null);
      serverStatusSpinner.setVisible(true);
    });
  }

  public void logToConsole(String message, boolean isError, boolean isBuildLog) {
    var consolePanel = isBuildLog ? buildLogsConsole : serverLogsConsole;
    if (consolePanel != null) {
      consolePanel.appendText(message, isError);
    }
  }

  public LlamaSettingsState getCurrentState() {
    var state = new LlamaSettingsState();

    state.setServerPort(serverPreferencesForm.getServerPort());
    state.setContextSize(serverPreferencesForm.getContextSize());
    state.setThreads(serverPreferencesForm.getThreads());
    state.setAdditionalParameters(serverPreferencesForm.getAdditionalParameters());
    state.setAdditionalBuildParameters(serverPreferencesForm.getAdditionalBuildParameters());
    state.setAdditionalEnvironmentVariables(
        serverPreferencesForm.getAdditionalEnvironmentVariables());

    state.setTopK(settingsState.getTopK());
    state.setTopP(settingsState.getTopP());
    state.setMinP(settingsState.getMinP());
    state.setRepeatPenalty(settingsState.getRepeatPenalty());

    var modelPreferencesForm = serverPreferencesForm.getLlamaModelPreferencesForm();
    state.setCustomLlamaModelPath(modelPreferencesForm.getCustomLlamaModelPath());
    state.setUseCustomModel(modelPreferencesForm.isUseCustomLlamaModel());
    state.setHuggingFaceModel(modelPreferencesForm.getSelectedModel());
    state.setLocalModelPromptTemplate(modelPreferencesForm.getPromptTemplate());

    state.setCodeCompletionsEnabled(settingsState.isCodeCompletionsEnabled());

    return state;
  }

  public void resetForm() {
    serverPreferencesForm.resetForm(settingsState);
  }

  public void resetForm(LlamaSettingsState newState) {
    serverPreferencesForm.resetForm(newState);
  }

  private void setTabState(int tabIndex, boolean enabled, String disabledTooltip) {
    tabbedPane.setEnabledAt(tabIndex, enabled);
    tabbedPane.setToolTipTextAt(tabIndex, enabled ? null : disabledTooltip);
  }
}