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

import com.intellij.openapi.util.SystemInfo;
import java.util.Objects;

public class Platform {

    Platform() {
        /*
         * distribution not determined yet.
         * A possible impl exists in jbosstools-base/usage:
         * https://github.com/jbosstools/jbosstools-base/blob/master/usage/plugins/org.jboss.tools.usage/src/org/jboss/tools/usage/internal/environment/eclipse/LinuxSystem.java
         */
        this(SystemInfo.OS_NAME, null, SystemInfo.OS_VERSION);
    }

    Platform(String name, String distribution, String version) {
        this.name = name;
        this.distribution = distribution;
        this.version = version;
    }

    private final String name;
    private final String distribution;
    private final String version;

    public String getName() {
        return name;
    }

    public String getDistribution() {
        return distribution;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Platform platform)) return false;
      return Objects.equals(name, platform.name)
                && Objects.equals(distribution, platform.distribution)
                && Objects.equals(version, platform.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, distribution, version);
    }
}
