package com.geoplan.rfid.agent.util;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny JSON helper. The agent only ever reads {"sessionId":"..."} and only ever
 * writes flat objects and string arrays, so a full parser would be dead weight.
 */
public final class Json {

    private Json() {
    }

    /**
     * Reads a top level string field. Returns null when the field is absent or
     * is not a JSON string.
     */
    public static String readString(String json, String field) {
        if (json == null) {
            return null;
        }

        Pattern pattern = Pattern.compile(
                "\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
                Pattern.DOTALL
        );

        Matcher matcher = pattern.matcher(json);

        if (!matcher.find()) {
            return null;
        }

        return unescape(matcher.group(1));
    }

    public static String stringArrayBody(String field, Collection<String> values) {
        StringBuilder body = new StringBuilder();

        body.append("{\"").append(escape(field)).append("\":[");

        boolean first = true;

        for (String value : values) {
            if (!first) {
                body.append(',');
            }

            body.append('"').append(escape(value)).append('"');
            first = false;
        }

        body.append("]}");

        return body.toString();
    }

    /**
     * Builds a flat object from alternating key and value arguments. A null
     * value is written as JSON null, a Number or Boolean is written bare, and
     * anything else is written as a quoted string.
     */
    public static String object(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected alternating keys and values");
        }

        StringBuilder body = new StringBuilder("{");

        for (int index = 0; index < keysAndValues.length; index += 2) {
            if (index > 0) {
                body.append(',');
            }

            body.append('"').append(escape(String.valueOf(keysAndValues[index]))).append("\":");

            Object value = keysAndValues[index + 1];

            if (value == null) {
                body.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                body.append(value);
            } else {
                body.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }

        return body.append('}').toString();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);

            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }

        return escaped.toString();
    }

    private static String unescape(String value) {
        StringBuilder unescaped = new StringBuilder(value.length());

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);

            if (character != '\\' || index + 1 >= value.length()) {
                unescaped.append(character);
                continue;
            }

            char next = value.charAt(++index);

            switch (next) {
                case 'n' -> unescaped.append('\n');
                case 'r' -> unescaped.append('\r');
                case 't' -> unescaped.append('\t');
                case 'b' -> unescaped.append('\b');
                case 'f' -> unescaped.append('\f');
                case 'u' -> {
                    if (index + 4 < value.length()) {
                        unescaped.append((char) Integer.parseInt(value.substring(index + 1, index + 5), 16));
                        index += 4;
                    }
                }
                default -> unescaped.append(next);
            }
        }

        return unescaped.toString();
    }
}
