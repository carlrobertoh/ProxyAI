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

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import java.util.Arrays;

public class Plugin extends Application {

    public static final class Factory {
        public Plugin create(ClassLoader classLoader) {
            IdeaPluginDescriptor descriptor = getPluginDescriptor(classLoader);
            if (descriptor == null) {
                return null;
            }
            return create(descriptor.getName(), descriptor.getVersion());
        }

        public Plugin create(String name, String version) {
            return new Plugin(name, version);
        }

        private IdeaPluginDescriptor getPluginDescriptor(ClassLoader classLoader) {
            return Arrays.stream(PluginManagerCore.getPlugins())
                    .filter(descriptor -> classLoader.equals(descriptor.getPluginClassLoader()))
                    .findFirst()
                    .orElse(null);
        }
    }

    Plugin(String name, String version) {
        super(name, version);
    }

    @Override
    public Plugin property(String key, String value) {
        super.property(key, value);
        return this;
    }

}
