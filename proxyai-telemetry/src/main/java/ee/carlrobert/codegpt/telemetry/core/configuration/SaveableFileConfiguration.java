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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.time.LocalDate;

public class SaveableFileConfiguration extends FileConfiguration {

    public SaveableFileConfiguration(Path file) {
        super(file);
    }

    public void save() throws IOException {
        if (path == null) {
            return;
        }
        File file = path.toFile();
        file.createNewFile(); // ensure exists
        try (Writer writer = new FileWriter(file)) {
            properties.get().store(writer, "updated " + LocalDate.now());
        }
    }
}
