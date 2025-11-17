package ee.carlrobert.codegpt.settings.advanced;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.PortField;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UI;
import com.intellij.util.ui.components.BorderLayoutPanel;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.settings.configuration.ConfigurationSettings;
import ee.carlrobert.codegpt.ui.UIUtil;
import java.awt.event.ItemEvent;
import java.net.Proxy;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

public class AdvancedSettingsComponent {

  private final JPanel mainPanel;
  private final ComboBox<Proxy.Type> proxyTypeComboBox;
  private final JBTextField proxyHostField;
  private final PortField proxyPortField;
  private final JBCheckBox proxyAuthCheckbox;
  private final JBTextField proxyAuthUsername;
  private final JBPasswordField proxyAuthPassword;
  private final PortField connectionTimeoutField;
  private final PortField readTimeoutField;
  private final JBCheckBox debugModeCheckBox;

  public AdvancedSettingsComponent(AdvancedSettingsState advancedSettings) {
    proxyTypeComboBox = new ComboBox<>(new Proxy.Type[]{
        Proxy.Type.SOCKS,
        Proxy.Type.HTTP,
        Proxy.Type.DIRECT,
    });
    proxyTypeComboBox.setSelectedItem(advancedSettings.getProxyType());
    proxyHostField = new JBTextField(advancedSettings.getProxyHost(), 20);
    proxyPortField = new PortField();
    proxyAuthCheckbox = new JBCheckBox(CodeGPTBundle.get(
        "advancedSettingsConfigurable.proxy.authCheckBoxField.label"));
    proxyAuthUsername = new JBTextField(20);
    proxyAuthUsername.setEnabled(advancedSettings.isProxyAuthSelected());
    proxyAuthPassword = new JBPasswordField();
    proxyAuthPassword.setColumns(20);
    proxyAuthPassword.setEnabled(advancedSettings.isProxyAuthSelected());
    proxyAuthCheckbox.addItemListener(itemEvent -> {
      proxyAuthUsername.setEnabled(itemEvent.getStateChange() == ItemEvent.SELECTED);
      proxyAuthPassword.setEnabled(itemEvent.getStateChange() == ItemEvent.SELECTED);
    });
    connectionTimeoutField = new PortField(advancedSettings.getConnectTimeout());
    readTimeoutField = new PortField(advancedSettings.getReadTimeout());

    debugModeCheckBox = new JBCheckBox(
        "Enable debug mode",
        ConfigurationSettings.getState().getDebugModeEnabled()
    );

    mainPanel = FormBuilder.createFormBuilder()
        .addComponent(new TitledSeparator(CodeGPTBundle.get(
            "advancedSettingsConfigurable.proxy.title")))
        .addComponent(createProxySettingsForm())
        .addVerticalGap(4)
        .addComponent(new TitledSeparator(
            CodeGPTBundle.get("advancedSettingsConfigurable.connectionSettings.title")))
        .addComponent(createConnectionSettingsForm())
        .addVerticalGap(4)
        .addComponent(new TitledSeparator("Debug"))
        .addComponent(new BorderLayoutPanel()
            .addToLeft(debugModeCheckBox)
            .withBorder(JBUI.Borders.emptyLeft(16)))
        .addComponentFillVertically(new JPanel(), 0)
        .getPanel();
  }

  private JPanel createConnectionSettingsForm() {
    var panel = FormBuilder.createFormBuilder()
        .addLabeledComponent(
            CodeGPTBundle.get(
                "advancedSettingsConfigurable.connectionSettings.connectionTimeout.label"),
            connectionTimeoutField)
        .addLabeledComponent(
            CodeGPTBundle.get("advancedSettingsConfigurable.connectionSettings.readTimeout.label"),
            readTimeoutField)
        .getPanel();
    panel.setBorder(JBUI.Borders.emptyLeft(16));
    return panel;
  }

  public JPanel getPanel() {
    return mainPanel;
  }

  private JComponent createProxySettingsForm() {
    var proxyPanel = new JPanel();
    proxyPanel.setBorder(JBUI.Borders.emptyLeft(16));
    proxyPanel.setLayout(new BoxLayout(proxyPanel, BoxLayout.PAGE_AXIS));

    var proxyTypePanel = UIUtil.createPanel(
        proxyTypeComboBox,
        CodeGPTBundle.get("advancedSettingsConfigurable.proxy.typeComboBoxField.label"),
        false);
    var proxyHostPanel = UIUtil.createPanel(
        proxyHostField,
        CodeGPTBundle.get("advancedSettingsConfigurable.proxy.hostField.label"),
        false);
    var proxyPortPanel = UIUtil.createPanel(
        proxyPortField,
        CodeGPTBundle.get("shared.port"),
        false);
    UIUtil.setEqualLabelWidths(proxyTypePanel, proxyHostPanel);
    UIUtil.setEqualLabelWidths(proxyPortPanel, proxyHostPanel);

    proxyPanel.add(proxyTypePanel);
    proxyPanel.add(proxyHostPanel);
    proxyPanel.add(proxyPortPanel);
    proxyPanel.add(UI.PanelFactory
        .panel(proxyAuthCheckbox)
        .createPanel());

    var proxyUsernamePanel = UIUtil.createPanel(proxyAuthUsername,
        CodeGPTBundle.get("advancedSettingsConfigurable.proxy.usernameField.label"),
        false);
    var proxyPasswordPanel = UIUtil.createPanel(proxyAuthPassword,
        CodeGPTBundle.get("advancedSettingsConfigurable.proxy.passwordField.label"),
        false);
    UIUtil.setEqualLabelWidths(proxyPasswordPanel, proxyUsernamePanel);

    var proxyAuthPanel = FormBuilder.createFormBuilder()
        .addVerticalGap(4)
        .addComponent(proxyUsernamePanel)
        .addComponent(proxyPasswordPanel)
        .getPanel();
    proxyAuthPanel.setBorder(JBUI.Borders.emptyLeft(16));
    proxyPanel.add(proxyAuthPanel);

    return proxyPanel;
  }

  public AdvancedSettingsState getCurrentFormState() {
    var state = new AdvancedSettingsState();
    state.setProxyType((Proxy.Type) proxyTypeComboBox.getSelectedItem());
    state.setProxyHost(proxyHostField.getText().trim());
    state.setProxyPort(proxyPortField.getNumber());
    state.setProxyAuthSelected(proxyAuthCheckbox.isSelected());
    state.setProxyUsername(proxyAuthUsername.getText().trim());
    state.setProxyPassword(new String(proxyAuthPassword.getPassword()));
    state.setConnectTimeout(connectionTimeoutField.getNumber());
    state.setReadTimeout(readTimeoutField.getNumber());
    return state;
  }

  public void resetForm() {
    var advancedSettings = AdvancedSettings.getCurrentState();
    proxyTypeComboBox.setSelectedItem(advancedSettings.getProxyType());
    proxyHostField.setText(advancedSettings.getProxyHost());
    proxyPortField.setNumber(advancedSettings.getProxyPort());
    proxyAuthCheckbox.setSelected(advancedSettings.isProxyAuthSelected());
    proxyAuthUsername.setText(advancedSettings.getProxyUsername());
    proxyAuthPassword.setText(advancedSettings.getProxyPassword());
    connectionTimeoutField.setNumber(advancedSettings.getConnectTimeout());
    readTimeoutField.setNumber(advancedSettings.getReadTimeout());
    debugModeCheckBox.setSelected(ConfigurationSettings.getState().getDebugModeEnabled());
  }

  public boolean isDebugModeEnabled() {
    return debugModeCheckBox.isSelected();
  }

  public void setDebugModeEnabled(boolean enabled) {
    debugModeCheckBox.setSelected(enabled);
  }
}
