package com.geoplan.rfid.agent.config;

import com.geoplan.rfid.agent.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration lookup. A -D system property wins over an environment variable,
 * which wins over agent.env in the working directory, then the desk default.
 * Nothing is hardcoded in source except non secret desk defaults.
 */
public final class Env {

    private static final Map<String, String> FILE_VALUES = loadEnvFile();

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

        String fileValue = FILE_VALUES.get(key);

        if (fileValue != null && !fileValue.isEmpty()) {
            return fileValue;
        }

        return defaultValue;
    }

    private static Map<String, String> loadEnvFile() {
        Path file = Path.of("agent.env").toAbsolutePath().normalize();
        Map<String, String> values = new HashMap<>();

        if (!Files.exists(file)) {
            return values;
        }

        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                // Some Windows editors save UTF-8 with a byte order mark.
                String trimmed = line.startsWith("\uFEFF") ? line.substring(1).trim() : line.trim();

                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                int separator = trimmed.indexOf('=');

                if (separator < 1) {
                    continue;
                }

                String key = trimmed.substring(0, separator).trim();
                String value = trimmed.substring(separator + 1).trim();

                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1).trim();
                }

                values.put(key, value);
            }

            Log.info("Loaded configuration from " + file);
        } catch (IOException e) {
            Log.warn("Could not read configuration from " + file, e);
        }

        return values;
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
