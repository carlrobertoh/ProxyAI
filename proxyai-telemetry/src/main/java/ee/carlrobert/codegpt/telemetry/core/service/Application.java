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

import java.util.AbstractMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class Application {

    private final String name;
    private final String version;
    private final Map<String, String> properties = new HashMap<>();

    Application(String name, String version) {
        this.name = name;
        this.version = version;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public Application property(String key, String value) {
        this.properties.put(key, value);
        return this;
    }

    public Collection<AbstractMap.SimpleEntry<String, Object>> getProperties() {
        return properties.entrySet().stream()
                .map(entry -> new AbstractMap.SimpleEntry<String, Object>(entry))
                .toList();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Application that)) return false;
        return Objects.equals(name, that.name)
                && Objects.equals(version, that.version)
                && Objects.equals(properties, that.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, version, properties);
    }
}
