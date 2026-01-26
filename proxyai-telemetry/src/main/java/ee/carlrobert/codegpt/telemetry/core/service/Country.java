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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;
import ee.carlrobert.codegpt.telemetry.core.util.Lazy;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * A class that provides the country for a given Timezone.
 * The mapping that this is based on relies on data provided the "countries-and-timezones" project
 * at https://github.com/manuelmhtr/countries-and-timezones
 */
public class Country {

    private static final Logger LOGGER = Logger.getInstance(Country.class);

    private static final String TIMEZONES = "/timezones.json";
    private static final String KEY_COUNTRY = "c";
    private static final String KEY_ALTERNATIVE = "a";

    private static final Country INSTANCE = new Country();

    public static Country getInstance() {
        return INSTANCE;
    }

    private final Lazy<Map<String, Map<String, String>>> timezones = new Lazy<>(() -> deserialize(TIMEZONES));

    protected Country() {
        // for testing purposes
    }

    public String get(TimeZone timeZone) {
        if (timeZone == null) {
            return null;
        }
        return get(timeZone.getID());
    }

    public String get(String timezoneId) {
        Map<String, String> timezone = timezones.get().get(timezoneId);
        if (timezone == null) {
            return null;
        }
        String abbreviation = timezone.get(KEY_COUNTRY);
        if (abbreviation != null) {
            return abbreviation;
        }
        String alternative = timezone.get(KEY_ALTERNATIVE);
        if (alternative == null) {
            return null;
        }
        return get(alternative);
    }

    private <V> Map<String, V> deserialize(String file) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            InputStream input = getClass().getResourceAsStream(file);
            TypeReference<Map<String, V>> typeRef = new TypeReference<>() {};
            return mapper.readValue(input, typeRef);
        } catch (IOException e) {
            LOGGER.warn("Could not load file " + file, e);
            return new HashMap<>();
        }
    }
}
