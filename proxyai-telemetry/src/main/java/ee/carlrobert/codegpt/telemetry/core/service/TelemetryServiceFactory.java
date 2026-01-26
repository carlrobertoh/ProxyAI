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
package ee.carlrobert.codegpt.telemetry.core.service;

import com.intellij.openapi.project.DumbAware;
import ee.carlrobert.codegpt.telemetry.core.IMessageBroker;
import ee.carlrobert.codegpt.telemetry.core.configuration.TelemetryConfiguration;
import ee.carlrobert.codegpt.telemetry.core.service.segment.SegmentBroker;
import ee.carlrobert.codegpt.telemetry.core.service.segment.SegmentConfiguration;

public class TelemetryServiceFactory implements DumbAware {

    private final IDE ide = new IDE.Factory()
            .create()
            .setJavaVersion();

    public TelemetryService create(ClassLoader classLoader) {
        Environment environment = new Environment.Builder()
                .ide(ide)
                .plugin(classLoader)
                .build();
        TelemetryConfiguration configuration = TelemetryConfiguration.getInstance();
        IMessageBroker broker = createSegmentBroker(configuration.isDebug(), classLoader, environment);
        return new TelemetryService(configuration, broker);
    }

    private IMessageBroker createSegmentBroker(boolean isDebug, ClassLoader classLoader, Environment environment) {
        SegmentConfiguration brokerConfiguration = new SegmentConfiguration(classLoader);
        return new SegmentBroker(
                isDebug,
                UserId.INSTANCE.get(),
                environment,
                brokerConfiguration);
    }

}
