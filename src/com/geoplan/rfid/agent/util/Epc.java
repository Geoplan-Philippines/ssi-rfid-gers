package com.geoplan.rfid.agent.util;

import java.util.regex.Pattern;

/**
 * EPC normalisation. The middleware stores uppercase hex, so every read is
 * trimmed, stripped of separators and uppercased before it is queued.
 */
public final class Epc {

    private static final Pattern HEX = Pattern.compile("^[0-9A-F]+$");

    private Epc() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }

        return raw.replaceAll("[\\s:-]+", "").trim().toUpperCase();
    }

    public static boolean isValid(String normalized) {
        return normalized != null
                && !normalized.isEmpty()
                && normalized.length() % 2 == 0
                && HEX.matcher(normalized).matches();
    }

    public static String fromBytes(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);

        for (byte value : bytes) {
            hex.append(String.format("%02X", value));
        }

        return hex.toString();
    }
}
