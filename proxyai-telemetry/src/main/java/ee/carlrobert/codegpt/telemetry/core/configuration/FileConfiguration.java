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

import com.intellij.openapi.diagnostic.Logger;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;

public class FileConfiguration extends AbstractConfiguration {

    private static final Logger LOGGER = Logger.getInstance(FileConfiguration.class);

    protected final Path path;

    public FileConfiguration(Path path) {
        this.path = path;
    }

    @Override
    protected Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream in = createInputStream(path)) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not load properties file " + (path == null? "" : path.toAbsolutePath()));
        }
        return properties;
    }

    protected InputStream createInputStream(Path path) throws IOException {
        if (path == null) {
            return null;
        }
        File file = path.toFile();
        if (!file.exists()) {
            return null;
        }
        return new FileInputStream(file);
    }
}
