package com.geoplan.rfid.agent.util;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Minimal stdout logger. No dependencies, one line per event, timestamp first so
 * desk operators can correlate agent logs with middleware logs.
 */
public final class Log {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private Log() {
    }

    public static void info(String message) {
        write("INFO", message, null);
    }

    public static void warn(String message) {
        write("WARN", message, null);
    }

    public static void warn(String message, Throwable error) {
        write("WARN", message, error);
    }

    public static void error(String message) {
        write("ERROR", message, null);
    }

    public static void error(String message, Throwable error) {
        write("ERROR", message, error);
    }

    private static void write(String level, String message, Throwable error) {
        String line = TIMESTAMP.format(ZonedDateTime.now())
                + " " + level
                + " [" + Thread.currentThread().getName() + "] "
                + message;

        if (level.equals("ERROR")) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }

        if (error != null) {
            error.printStackTrace(level.equals("ERROR") ? System.err : System.out);
        }
    }
}
