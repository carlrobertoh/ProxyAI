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

import com.intellij.openapi.diagnostic.Logger;
import ee.carlrobert.codegpt.telemetry.core.util.Directories;
import ee.carlrobert.codegpt.telemetry.core.util.FileUtils;
import ee.carlrobert.codegpt.telemetry.core.util.Lazy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class UserId {

    private static final Logger LOGGER = Logger.getInstance(UserId.class);

    public static final UserId INSTANCE = new UserId();
    private static final Path UUID_FILE = Directories.PATH.resolve("anonymousId");
    private static final Pattern UUID_REGEX =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private final Lazy<String> uuid = new Lazy<>(() -> loadOrCreate(UUID_FILE));

    /** for testing purposes */
    protected UserId() {}

    public String get() {
        return uuid.get();
    }

    private String loadOrCreate(Path file) {
        String uuid;
        if (exists(file)) {
            uuid = load(file);
            if (isValid(uuid)) {
                return uuid;
            }
        }
        uuid = create();
        write(uuid, file);
        return uuid;
    }

    /** for testing purposes */
    protected boolean exists(Path file) {
        return Files.exists(file);
    }

    /** for testing purposes */
    protected String load(Path uuidFile) {
        String uuid = null;
        try(Stream<String> lines = Files.lines(uuidFile)) {
            uuid = lines
                    .filter(l -> !l.isBlank())
                    .findAny()
                    .map(String::trim)
                    .orElse(null);
        } catch (IOException e) {
            LOGGER.warn("Could not read anonymous UUID file at " + uuidFile.toAbsolutePath(), e);
        }
        return uuid;
    }

    private boolean isValid(String uuid) {
        if (uuid == null) {
            return false;
        }
        return UUID_REGEX.matcher(uuid).matches();
    }

    private String create() {
        return UUID.randomUUID().toString();
    }

    /** for testing purposes */
    protected void write(String uuid, Path uuidFile) {
        try {
            FileUtils.createFileAndParent(uuidFile);
            FileUtils.write(uuid, uuidFile);
        } catch (IOException e) {
            LOGGER.warn("Could not write anonymous UUID to file at " + UUID_FILE.toAbsolutePath(), e);
        }
    }
}
