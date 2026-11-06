package com.geoplan.rfid.agent.config;

import com.geoplan.rfid.agent.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every knob the agent has. Values come from -D system properties or the
 * environment or agent.env, defaults target a single reader desk while
 * supporting multiple controllers via numbered blocks (READER_1_*, READER_2_*)
 * or comma-separated lists (READER_HOST=ip1,ip2 / READER_ID=id1,id2).
 */
public final class AgentConfig {

    /** What the agent does when /scan/start arrives while another session is running. */
    public enum StartConflictPolicy {
        /** Close the running session, then start the new one. Default. */
        TAKEOVER,
        /** Keep the running session and answer 409. */
        REJECT
    }

    /** Configuration for an individual RFID controller / reader. */
    public record ReaderConfig(
            int index,
            String readerId,
            String readerHost,
            int readerPort,
            String readerName,
            boolean simulated,
            int simulatedTagCount,
            long simulatedIntervalMs,
            int readerTagFilteringTimeMs,
            long readerWatchdogIntervalMs,
            String readerAntennas,
            long stopFlushTimeoutMs
    ) {
        public String endpointKey() {
            return simulated
                    ? "simulator:" + readerName
                    : readerHost.toLowerCase() + ":" + readerPort;
        }
    }

    /* All configured readers (always at least 1). */
    public final List<ReaderConfig> readers;

    /* Primary reader fields kept for backward compatibility. */
    public final String readerHost;
    public final int readerPort;
    public final String readerName;
    public final boolean simulated;
    public final int simulatedTagCount;
    public final long simulatedIntervalMs;
    public final int readerTagFilteringTimeMs;
    public final long readerWatchdogIntervalMs;
    public final String readerAntennas;
    public final long stopFlushTimeoutMs;

    /* Control server */
    public final String bindAddress;
    public final int controlPort;
    public final String controlApiKey;

    /* TLS */
    public final String keystorePath;
    public final String keystorePassword;
    public final String keystoreType;
    public final String extraSubjectAltNames;

    /* Middleware */
    public final String middlewareBaseUrl;
    public final String readsPathTemplate;
    public final String middlewareApiKey;
    public final long middlewareTimeoutMs;

    /* Outbound command polling */
    public final String readerId;
    public final String commandPollPath;
    public final String commandAckPathTemplate;
    public final long commandPollRequestTimeoutMs;
    public final long commandRetryDelayMs;

    /* Batching */
    public final int batchSize;
    public final long flushIntervalMs;
    public final int maxPendingEpcs;

    public final StartConflictPolicy startConflictPolicy;

    private AgentConfig() {
        simulatedTagCount = Env.integer("SIMULATOR_TAG_COUNT", 5, 1, 10000);
        simulatedIntervalMs = Env.millis("SIMULATOR_INTERVAL_MS", 500, 10, 60000);
        readerTagFilteringTimeMs = Env.integer("READER_TAG_FILTERING_TIME_MS", 0, 0, 65535);
        readerWatchdogIntervalMs = Env.millis("READER_WATCHDOG_INTERVAL_MS", 5000, 500, 300000);
        stopFlushTimeoutMs = Env.millis("EPC_STOP_FLUSH_TIMEOUT_MS", 15000, 1000, 120000);

        readers = loadReaders(
                simulatedTagCount,
                simulatedIntervalMs,
                readerTagFilteringTimeMs,
                readerWatchdogIntervalMs,
                stopFlushTimeoutMs
        );

        ReaderConfig primary = readers.get(0);
        readerHost = primary.readerHost();
        readerPort = primary.readerPort();
        readerName = primary.readerName();
        simulated = primary.simulated();
        readerAntennas = primary.readerAntennas();
        readerId = primary.readerId();

        bindAddress = Env.string("AGENT_BIND_ADDRESS", "127.0.0.1");
        controlPort = Env.integer("AGENT_CONTROL_PORT", 8443, 1, 65535);
        controlApiKey = Env.optional("AGENT_API_KEY");

        keystorePath = Env.string("AGENT_TLS_KEYSTORE", "certs/agent-keystore.p12");
        keystorePassword = Env.string("AGENT_TLS_KEYSTORE_PASSWORD", "changeit");
        keystoreType = Env.string("AGENT_TLS_KEYSTORE_TYPE", "PKCS12");
        extraSubjectAltNames = Env.optional("AGENT_TLS_SAN");

        middlewareBaseUrl = trimTrailingSlash(Env.string("MIDDLEWARE_BASE_URL", "http://localhost:8000"));
        readsPathTemplate = Env.string(
                "MIDDLEWARE_READS_PATH",
                "/api/v1/epc-scan-processing/sessions/{sessionId}/reads"
        );
        middlewareApiKey = Env.optional("MIDDLEWARE_API_KEY");
        middlewareTimeoutMs = Env.millis("MIDDLEWARE_TIMEOUT_MS", 5000, 250, 120000);

        commandPollPath = Env.string(
                "COMMAND_POLL_PATH",
                "/api/v1/reader-agent/commands/poll"
        );
        commandAckPathTemplate = Env.string(
                "COMMAND_ACK_PATH",
                "/api/v1/reader-agent/commands/{commandId}/ack"
        );
        commandPollRequestTimeoutMs = Env.millis(
                "COMMAND_POLL_REQUEST_TIMEOUT_MS", 30000, 5000, 120000
        );
        commandRetryDelayMs = Env.millis("COMMAND_RETRY_DELAY_MS", 1000, 250, 60000);

        batchSize = Env.integer("EPC_BATCH_SIZE", 200, 1, 1000);
        flushIntervalMs = Env.millis("EPC_FLUSH_INTERVAL_MS", 1000, 100, 60000);
        maxPendingEpcs = Env.integer("EPC_MAX_PENDING", 50000, 1000, 1000000);

        startConflictPolicy = Env.string("SCAN_START_CONFLICT", "takeover").equalsIgnoreCase("reject")
                ? StartConflictPolicy.REJECT
                : StartConflictPolicy.TAKEOVER;
    }

    public static AgentConfig load() {
        return new AgentConfig();
    }

    public ReaderConfig primaryReader() {
        return readers.get(0);
    }

    public String readsUrl(String sessionId) {
        return middlewareBaseUrl + readsPathTemplate.replace("{sessionId}", sessionId);
    }

    public String commandPollUrl() {
        return middlewareBaseUrl + commandPollPath;
    }

    public String commandAckUrl(String commandId) {
        return middlewareBaseUrl + commandAckPathTemplate.replace("{commandId}", commandId);
    }

    /** Logs the effective configuration with secrets redacted. */
    public void logSummary() {
        Log.info("Readers (" + readers.size() + ")     :");
        boolean anyReaderId = false;
        for (ReaderConfig rc : readers) {
            if (!rc.readerId().isEmpty()) {
                anyReaderId = true;
            }
            Log.info("  [#" + rc.index() + "] " + rc.readerName()
                    + " -> " + (rc.simulated() ? "SIMULATOR" : rc.readerHost() + ":" + rc.readerPort())
                    + " | antennas=" + rc.readerAntennas()
                    + " | readerId=" + (rc.readerId().isEmpty() ? "MISSING" : rc.readerId()));
        }
        Log.info("Control server  : https://" + bindAddress + ":" + controlPort
                + " (auth=" + (controlApiKey.isEmpty() ? "none" : "x-api-key") + ")");
        Log.info("TLS keystore    : " + keystorePath + " (" + keystoreType + ")");
        Log.info("Middleware      : " + readsUrl("{sessionId}")
                + " (x-api-key=" + (middlewareApiKey.isEmpty() ? "MISSING" : "set") + ")");
        Log.info("Command polling : " + commandPollUrl());
        Log.info("Batching        : size=" + batchSize + " flush=" + flushIntervalMs + "ms"
                + " maxPending=" + maxPendingEpcs + " stopFlush=" + stopFlushTimeoutMs + "ms");
        Log.info("Start conflict  : " + startConflictPolicy);

        if (middlewareApiKey.isEmpty()) {
            Log.warn("MIDDLEWARE_API_KEY is not set. Reads and command polling will be rejected.");
        }
        if (!anyReaderId) {
            Log.warn("READER_ID is not set on any reader. Outbound command polling is disabled.");
        }
    }

    private static List<ReaderConfig> loadReaders(
            int simulatedTagCount,
            long simulatedIntervalMs,
            int defaultTagFilteringTimeMs,
            long readerWatchdogIntervalMs,
            long stopFlushTimeoutMs
    ) {
        List<String> hosts = splitList(Env.optional("READER_HOST"), "[,;]");
        List<String> ids = splitList(Env.optional("READER_ID"), "[,;]");
        List<String> ports = splitList(Env.optional("READER_PORT"), "[,;]");
        List<String> names = splitList(Env.optional("READER_NAME"), "[,;]");
        List<String> modes = splitList(Env.optional("READER_MODE"), "[,;]");
        List<String> antennasList = splitList(Env.optional("READER_ANTENNAS"), "[;|]");

        TreeSet<Integer> indices = new TreeSet<>(Env.readerIndices());
        int unnumberedCount = Math.max(hosts.size(), ids.size());
        for (int i = 1; i <= unnumberedCount; i++) {
            indices.add(i);
        }
        if (indices.isEmpty()) {
            indices.add(1);
        }

        List<ReaderConfig> result = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();

        int position = 0;
        for (int idx : indices) {
            String prefix = "READER_" + idx + "_";

            String host = Env.optional(prefix + "HOST");
            if (host.isEmpty()) {
                host = pickFromList(hosts, position, "192.168.1.100", true);
            }

            String portText = Env.optional(prefix + "PORT");
            if (portText.isEmpty()) {
                portText = pickFromList(ports, position, "9090", true);
            }
            int port = parsePort(prefix + "PORT", portText, 9090);

            String rawName = Env.optional(prefix + "NAME");
            if (rawName.isEmpty()) {
                if (position < names.size()) {
                    rawName = names.get(position);
                } else if (position == 0 && !names.isEmpty()) {
                    rawName = names.get(0);
                } else {
                    rawName = "Device" + idx;
                }
            }
            String name = ensureUniqueName(rawName, idx, usedNames);

            String mode = Env.optional(prefix + "MODE");
            if (mode.isEmpty()) {
                mode = pickFromList(modes, position, "hardware", true);
            }
            boolean isSimulated = mode.equalsIgnoreCase("simulator");

            String antennas = Env.optional(prefix + "ANTENNAS");
            if (antennas.isEmpty()) {
                antennas = pickFromList(antennasList, position, "1,2", true);
            }

            String id = Env.optional(prefix + "ID");
            if (id.isEmpty()) {
                id = pickFromList(ids, position, "", false);
            }

            int filteringMs = Env.isSet(prefix + "TAG_FILTERING_TIME_MS")
                    ? Env.integer(prefix + "TAG_FILTERING_TIME_MS", defaultTagFilteringTimeMs, 0, 65535)
                    : defaultTagFilteringTimeMs;

            result.add(new ReaderConfig(
                    idx,
                    id,
                    host,
                    port,
                    name,
                    isSimulated,
                    simulatedTagCount,
                    simulatedIntervalMs,
                    filteringMs,
                    readerWatchdogIntervalMs,
                    antennas,
                    stopFlushTimeoutMs
            ));
            position++;
        }

        return List.copyOf(result);
    }

    private static List<String> splitList(String raw, String regexDelimiter) {
        List<String> items = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return items;
        }
        for (String part : raw.split(regexDelimiter)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    private static String pickFromList(
            List<String> list,
            int index,
            String defaultValue,
            boolean reuseSingleAsDefault
    ) {
        if (index < list.size()) {
            return list.get(index);
        }
        if (reuseSingleAsDefault && list.size() == 1) {
            return list.get(0);
        }
        return defaultValue;
    }

    private static int parsePort(String key, String value, int defaultValue) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1 || parsed > 65535) {
                Log.warn("Config " + key + "=" + parsed + " is outside 1..65535. Using " + defaultValue + ".");
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException error) {
            Log.warn("Config " + key + "='" + value + "' is not a number. Using " + defaultValue + ".");
            return defaultValue;
        }
    }

    private static String ensureUniqueName(String candidate, int index, Set<String> usedNames) {
        String name = candidate.isBlank() ? "Device" + index : candidate.trim();
        if (usedNames.add(name)) {
            return name;
        }
        String fallback = name.equals("Device1") ? "Device" + index : name + "-" + index;
        while (!usedNames.add(fallback)) {
            fallback = fallback + "_" + index;
        }
        return fallback;
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value;

        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed;
    }
}
