package com.geoplan.rfid.agent.config;

import com.geoplan.rfid.agent.util.Log;

/**
 * Configuration lookup. A -D system property wins over an environment variable,
 * which wins over the built in desk default. Nothing is hardcoded in source
 * except non secret desk defaults.
 */
public final class Env {

    private Env() {
    }

    public static String string(String key, String defaultValue) {
        String systemValue = System.getProperty(key);

        if (systemValue != null && !systemValue.trim().isEmpty()) {
            return systemValue.trim();
        }

        String environmentValue = System.getenv(key);

        if (environmentValue != null && !environmentValue.trim().isEmpty()) {
            return environmentValue.trim();
        }

        return defaultValue;
    }

    public static String optional(String key) {
        return string(key, "");
    }

    public static int integer(String key, int defaultValue, int minimum, int maximum) {
        String value = string(key, Integer.toString(defaultValue));

        try {
            int parsed = Integer.parseInt(value);

            if (parsed < minimum || parsed > maximum) {
                Log.warn("Config " + key + "=" + parsed + " is outside " + minimum + ".." + maximum
                        + ". Using " + defaultValue + ".");
                return defaultValue;
            }

            return parsed;
        } catch (NumberFormatException e) {
            Log.warn("Config " + key + "='" + value + "' is not a number. Using " + defaultValue + ".");
            return defaultValue;
        }
    }

    public static long millis(String key, long defaultValue, long minimum, long maximum) {
        String value = string(key, Long.toString(defaultValue));

        try {
            long parsed = Long.parseLong(value);

            if (parsed < minimum || parsed > maximum) {
                Log.warn("Config " + key + "=" + parsed + " is outside " + minimum + ".." + maximum
                        + ". Using " + defaultValue + ".");
                return defaultValue;
            }

            return parsed;
        } catch (NumberFormatException e) {
            Log.warn("Config " + key + "='" + value + "' is not a number. Using " + defaultValue + ".");
            return defaultValue;
        }
    }
}
