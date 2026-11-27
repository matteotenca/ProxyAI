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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.diagnostic.Logger;
import ee.carlrobert.codegpt.telemetry.core.util.Lazy;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
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

    private Map<String, Map<String, String>> deserialize(String file) {
        try (Reader input = new InputStreamReader(getClass().getResourceAsStream(file), StandardCharsets.UTF_8)) {
            // numeric fields (e.g. "u": -180) are read as strings; only "c" and "a" are used
            return new Gson().fromJson(input, new TypeToken<Map<String, Map<String, String>>>() {}.getType());
        } catch (IOException e) {
            LOGGER.warn("Could not load file " + file, e);
            return new HashMap<>();
        }
    }
}
