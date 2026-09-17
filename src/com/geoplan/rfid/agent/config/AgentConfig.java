package com.geoplan.rfid.agent.config;

import com.geoplan.rfid.agent.util.Log;

/**
 * Every knob the agent has. Values come from -D system properties or the
 * environment or agent.env, defaults target a single reader desk.
 */
public final class AgentConfig {

    /** What the agent does when /scan/start arrives while another session is running. */
    public enum StartConflictPolicy {
        /** Close the running session, then start the new one. Default. */
        TAKEOVER,
        /** Keep the running session and answer 409. */
        REJECT
    }

    /* Reader */
    public final String readerHost;
    public final int readerPort;
    public final String readerName;
    public final boolean simulated;
    public final int simulatedTagCount;
    public final long simulatedIntervalMs;
    public final int readerTagFilteringTimeMs;
    public final long readerWatchdogIntervalMs;

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

    /* Batching */
    public final int batchSize;
    public final long flushIntervalMs;
    public final int maxPendingEpcs;

    public final StartConflictPolicy startConflictPolicy;

    private AgentConfig() {
        readerHost = Env.string("READER_HOST", "192.168.1.100");
        readerPort = Env.integer("READER_PORT", 9090, 1, 65535);
        readerName = Env.string("READER_NAME", "Device1");
        simulated = Env.string("READER_MODE", "hardware").equalsIgnoreCase("simulator");
        simulatedTagCount = Env.integer("SIMULATOR_TAG_COUNT", 5, 1, 10000);
        simulatedIntervalMs = Env.millis("SIMULATOR_INTERVAL_MS", 500, 10, 60000);
        readerTagFilteringTimeMs = Env.integer("READER_TAG_FILTERING_TIME_MS", 0, 0, 65535);
        readerWatchdogIntervalMs = Env.millis("READER_WATCHDOG_INTERVAL_MS", 5000, 500, 300000);

        bindAddress = Env.string("AGENT_BIND_ADDRESS", "0.0.0.0");
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

    public String readsUrl(String sessionId) {
        return middlewareBaseUrl + readsPathTemplate.replace("{sessionId}", sessionId);
    }

    /** Logs the effective configuration with secrets redacted. */
    public void logSummary() {
        Log.info("Reader          : " + (simulated ? "SIMULATOR" : readerHost + ":" + readerPort)
                + " (name=" + readerName + ")");
        Log.info("Control server  : https://" + bindAddress + ":" + controlPort
                + " (auth=" + (controlApiKey.isEmpty() ? "none" : "x-api-key") + ")");
        Log.info("TLS keystore    : " + keystorePath + " (" + keystoreType + ")");
        Log.info("Middleware      : " + readsUrl("{sessionId}")
                + " (x-api-key=" + (middlewareApiKey.isEmpty() ? "MISSING" : "set") + ")");
        Log.info("Batching        : size=" + batchSize + " flush=" + flushIntervalMs + "ms"
                + " maxPending=" + maxPendingEpcs);
        Log.info("Start conflict  : " + startConflictPolicy);

        if (middlewareApiKey.isEmpty()) {
            Log.warn("MIDDLEWARE_API_KEY is not set. Reads will be rejected unless the middleware allows anonymous appends.");
        }
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value;

        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed;
    }
}
