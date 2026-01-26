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
package ee.carlrobert.codegpt.telemetry.core.configuration;

import ee.carlrobert.codegpt.telemetry.core.util.Lazy;
import java.util.Properties;

public abstract class AbstractConfiguration implements IConfiguration {

    protected final Lazy<Properties> properties = new Lazy<>(this::loadProperties);

    @Override
    public String get(String key) {
        return properties.get().getProperty(key);
    }

    @Override
    public void put(String key, String value) {
        properties.get().put(key, value);
    }

    protected abstract Properties loadProperties();
}
