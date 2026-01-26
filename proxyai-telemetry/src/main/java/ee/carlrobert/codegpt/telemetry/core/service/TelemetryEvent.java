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

import java.util.HashMap;
import java.util.Map;

public class TelemetryEvent {

    public enum Type {
        USER, ACTION, STARTUP, SHUTDOWN
    }

    private final Type type;
    private final String name;
    private final Map<String, String> properties;

    public TelemetryEvent(Type type, String name) {
        this(type, name, new HashMap<>());
    }

    public TelemetryEvent(Type type, String name, Map<String, String> properties) {
        this.type = type;
        this.name = name;
        this.properties = properties;
    }

    public Type getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public Map<String, String> getProperties() {
        return properties;
    }
}
