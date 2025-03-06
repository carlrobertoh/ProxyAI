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

import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UI;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Provides a {@link JPanel} with widgets for the Telemetry settings.
 */
public class TelemetryComponent {

    private static final String USAGE_DESCRIPTION =
            "Help ProxyAI improve its products by sending anonymous data about features and plugins used, "
                    + "hardware and software configuration.<br/>"
                    + "<br/>"
                    + "Please note that this will not include personal data or any sensitive Information.<br/>"
                    + "The data sent complies with the <a href=\"https://tryproxy.io/privacy\">Privacy Policy</a>.";

    private static final String COMPLETION_DESCRIPTION =
        "Help ProxyAI improve its products by allowing us to gather your code inputs and the completions we generate.<br/>"
            + "This data is essential for training our multi-line edit prediction model, which we're committed to releasing as open source to benefit the entire developer ecosystem.<br/>"
            + "<br/>"
            + "See our <a href=\"https://tryproxy.io/privacy\">Privacy Policy</a> for details.";

    private final JPanel panel;
    private final JBCheckBox usageStatisticsEnabled = new JBCheckBox("Send usage statistics");
    private final JBCheckBox completionStatisticsEnabled = new JBCheckBox("Send completion statistics");

    public TelemetryComponent() {
        this.panel = FormBuilder.createFormBuilder()
                .addComponent(
                    createCommentedPanel(usageStatisticsEnabled, USAGE_DESCRIPTION), 1)
                .addComponent(
                    createCommentedPanel(completionStatisticsEnabled, COMPLETION_DESCRIPTION), 1)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    private JPanel createCommentedPanel(JComponent component, String comment) {
         // @See com.intellij.internal.ui.ComponentPanelTestAction for more details on how to create comment panels
        return UI.PanelFactory.panel(component)
                .withComment(comment)
                .createPanel();
    }

    public JPanel getPanel() {
        return panel;
    }

    public JComponent getPreferredFocusedComponent() {
        return usageStatisticsEnabled;
    }

    public boolean getUsageStatisticsEnabled() {
        return usageStatisticsEnabled.isSelected();
    }

    public void setUsageStatisticsEnabled(boolean usageStatisticsEnabled) {
        this.usageStatisticsEnabled.setSelected(usageStatisticsEnabled);
    }

    public boolean getCompletionStatisticsEnabled() {
        return completionStatisticsEnabled.isSelected();
    }

    public void setCompletionStatisticsEnabled(boolean completionStatisticsEnabled) {
        this.completionStatisticsEnabled.setSelected(completionStatisticsEnabled);
    }
}
