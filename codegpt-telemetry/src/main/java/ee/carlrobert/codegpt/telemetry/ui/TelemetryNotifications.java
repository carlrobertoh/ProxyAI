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
package ee.carlrobert.codegpt.telemetry.ui;

import com.intellij.icons.AllIcons;
import com.intellij.notification.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAwareAction;
import ee.carlrobert.codegpt.telemetry.core.configuration.TelemetryConfiguration;
import ee.carlrobert.codegpt.telemetry.ui.utils.NotificationGroupFactory;
import java.io.IOException;

public class TelemetryNotifications {

    private static final Logger LOGGER = Logger.getInstance(TelemetryNotifications.class);

    private static final NotificationGroup QUERY_USER_CONSENT = NotificationGroupFactory.create(
            "Enable Telemetry",
            NotificationDisplayType.STICKY_BALLOON,
            true);

    private final NotificationGroup group;

    public TelemetryNotifications() {
        this(QUERY_USER_CONSENT);
    }

    TelemetryNotifications(NotificationGroup group) {
        this.group = group;
    }

    public void queryUserConsent() {
        Notification notification = group.createNotification(
                "Help ProxyAI improve its extensions by allowing them to collect anonymous usage data. " +
                "Read our <a href=\"https://tryproxy.io/privacy\">privacy statement</a> " +
                "and learn how to <a href=\"\">opt out</a>.",
        NotificationType.INFORMATION);
        notification.setTitle("Enable Telemetry");
        notification.setListener(new NotificationListener.UrlOpeningListener(false));
        DumbAwareAction accept = DumbAwareAction.create("Accept",
                e -> enableTelemetry(true, notification));
        DumbAwareAction deny = DumbAwareAction.create("Deny",
                e -> enableTelemetry(false, notification));
        DumbAwareAction later = DumbAwareAction.create("Later",
                e -> notification.expire());
        notification
                .addAction(accept)
                .addAction(deny)
                .addAction(later)
                .setIcon(AllIcons.General.TodoQuestion)
                .notify(null);
    }

    private static void enableTelemetry(boolean enabled, Notification notification) {
        TelemetryConfiguration configuration = TelemetryConfiguration.getInstance();
        configuration.setEnabled(enabled);
        try {
            configuration.save();
        } catch (IOException e) {
            LOGGER.warn("Could not save telemetry configuration.", e);
        } finally {
            notification.expire();
        }
    }
}
