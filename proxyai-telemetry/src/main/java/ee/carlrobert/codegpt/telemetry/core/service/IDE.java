/*******************************************************************************
 * Copyright (c) 2022 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package ee.carlrobert.codegpt.telemetry.core.service;

import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationNamesInfo;

public class IDE extends Application {

    public static final String PROP_JAVA_VERSION = "java_version";

    public static final class Factory {
        public IDE create() {
            return new IDE(
                    ApplicationNamesInfo.getInstance().getFullProductNameWithEdition(),
                    ApplicationInfo.getInstance().getFullVersion());
        }
    }

    IDE(String applicationName, String applicationVersion) {
        super(applicationName, applicationVersion);
    }

    public IDE setJavaVersion() {
        return setJavaVersion(System.getProperty("java.version"));
    }

    public IDE setJavaVersion(String version) {
        property(PROP_JAVA_VERSION, version);
        return this;
    }

    @Override
    public IDE property(String key, String value) {
        super.property(key, value);
        return this;
    }

}
