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

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public abstract class CompositeConfiguration implements IConfiguration {

    @Override
    public String get(final String key) {
        List<IConfiguration> configurations = getConfigurations();
        return (configurations == null ? Stream.<IConfiguration>empty() : configurations.stream())
                .filter(Objects::nonNull)
                .map(configuration -> configuration.get(key))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    protected abstract List<IConfiguration> getConfigurations();

}
