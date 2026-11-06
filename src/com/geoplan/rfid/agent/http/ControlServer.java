package com.geoplan.rfid.agent.http;

import com.geoplan.rfid.agent.config.AgentConfig;
import com.geoplan.rfid.agent.scan.ScanCoordinator;
import com.geoplan.rfid.agent.util.Json;
import com.geoplan.rfid.agent.util.Log;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * HTTPS control surface the middleware calls.
 *
 * POST /scan/start  {"sessionId":"<uuid>"}  start continuous inventory
 * POST /scan/stop   {"sessionId":"<uuid>"}  stop it
 * GET  /health                              reader and session state
 *
 * A non 2xx answer makes the middleware roll the document back to NEW, so start
 * only answers 200 once inventory is actually running.
 */
public final class ControlServer {

    /** Wide enough for a uuid or a cuid, narrow enough to be safe in a URL path. */
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9._~-]{1,64}");

    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final AgentConfig config;
    private final List<ScanCoordinator> coordinators;

    private HttpsServer server;
    private ExecutorService executor;

    public ControlServer(AgentConfig config, ScanCoordinator coordinator) {
        this(config, List.of(coordinator));
    }

    public ControlServer(AgentConfig config, List<ScanCoordinator> coordinators) {
        this.config = config;
        this.coordinators = List.copyOf(coordinators);
    }

    public void start() throws Exception {
        SSLContext sslContext = Tls.createContext(config);

        server = HttpsServer.create(new InetSocketAddress(config.bindAddress, config.controlPort), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext));

        server.createContext("/scan/start", exchange -> handleScan(exchange, true));
        server.createContext("/scan/stop", exchange -> handleScan(exchange, false));
        server.createContext("/health", this::handleHealth);
        server.createContext("/", this::handleUnknown);

        executor = Executors.newFixedThreadPool(4, task -> {
            Thread thread = new Thread(task, "control-http");
            thread.setDaemon(true);
            return thread;
        });

        server.setExecutor(executor);
        server.start();

        Log.info("Control server listening on https://" + config.bindAddress + ":" + config.controlPort);
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
        }

        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void handleScan(HttpExchange exchange, boolean isStart) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, Json.object("error", "method_not_allowed", "expected", "POST"));
                return;
            }

            if (!isAuthorised(exchange)) {
                respond(exchange, 401, Json.object("error", "unauthorized"));
                return;
            }

            String body = readBody(exchange);
            String sessionId = Json.readString(body, "sessionId");

            if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
                respond(exchange, 400, Json.object("error", "invalid_session_id",
                        "expected", "{\"sessionId\":\"<uuid>\"}"));
                return;
            }

            String readerId = Json.readString(body, "readerId");
            String readerName = Json.readString(body, "readerName");
            ScanCoordinator target = resolveCoordinator(sessionId, readerId, readerName, isStart);
            if (target == null) {
                respond(exchange, 404, Json.object(
                        "error", "reader_not_found",
                        "readerId", readerId,
                        "readerName", readerName
                ));
                return;
            }

            if (isStart) {
                respondToStart(exchange, target, target.start(sessionId));
            } else {
                respondToStop(exchange, target.stop(sessionId));
            }
        } catch (Exception e) {
            Log.error("Control request failed", e);
            respond(exchange, 500, Json.object("error", "internal_error", "message", String.valueOf(e.getMessage())));
        } finally {
            exchange.close();
        }
    }

    private ScanCoordinator resolveCoordinator(
            String sessionId,
            String readerId,
            String readerName,
            boolean isStart
    ) {
        if (readerId != null && !readerId.isBlank()) {
            for (ScanCoordinator coordinator : coordinators) {
                if (coordinator.matchesReaderId(readerId.trim())) {
                    return coordinator;
                }
            }
            return null;
        }

        if (readerName != null && !readerName.isBlank()) {
            for (ScanCoordinator coordinator : coordinators) {
                if (coordinator.matchesReaderName(readerName.trim())) {
                    return coordinator;
                }
            }
            return null;
        }

        for (ScanCoordinator coordinator : coordinators) {
            if (coordinator.hasSession(sessionId)) {
                return coordinator;
            }
        }

        if (isStart) {
            for (ScanCoordinator coordinator : coordinators) {
                if (coordinator.health().activeSessionId() == null) {
                    return coordinator;
                }
            }
        }

        return coordinators.get(0);
    }

    private void respondToStart(
            HttpExchange exchange,
            ScanCoordinator coordinator,
            ScanCoordinator.StartResult result
    ) throws IOException {
        switch (result.status()) {
            case STARTED, ALREADY_RUNNING -> respond(exchange, 200, Json.object(
                    "status", result.status() == ScanCoordinator.StartStatus.STARTED ? "started" : "already_running",
                    "sessionId", result.sessionId(),
                    "readerName", coordinator.readerConfig().readerName()
            ));
            case REPLACED -> respond(exchange, 200, Json.object(
                    "status", "started",
                    "sessionId", result.sessionId(),
                    "replacedSessionId", result.replacedSessionId(),
                    "readerName", coordinator.readerConfig().readerName()
            ));
            case CONFLICT -> respond(exchange, 409, Json.object(
                    "error", "session_already_active",
                    "sessionId", result.sessionId(),
                    "activeSessionId", result.replacedSessionId()
            ));
            case READER_UNAVAILABLE -> respond(exchange, 503, Json.object(
                    "error", "reader_unavailable",
                    "sessionId", result.sessionId(),
                    "reader", coordinator.health().readerTarget()
            ));
        }
    }

    private void respondToStop(HttpExchange exchange, ScanCoordinator.StopResult result) throws IOException {
        switch (result.status()) {
            case STOPPED -> respond(exchange, 200, Json.object(
                    "status", "stopped",
                    "sessionId", result.sessionId(),
                    "uniqueEpcs", result.unique(),
                    "sentEpcs", result.sent(),
                    "pendingEpcs", result.pending()
            ));
            case NOT_RUNNING -> respond(exchange, 200, Json.object(
                    "status", "not_running",
                    "sessionId", result.sessionId()
            ));
            case SESSION_MISMATCH -> respond(exchange, 200, Json.object(
                    "status", "not_running",
                    "sessionId", result.sessionId(),
                    "activeSessionId", result.activeSessionId()
            ));
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, Json.object("error", "method_not_allowed", "expected", "GET"));
                return;
            }

            java.util.List<String> readerEntries = new java.util.ArrayList<>();
            boolean allConnected = !coordinators.isEmpty();
            boolean anyRunning = false;
            String firstActiveSession = null;
            long totalUnique = 0;
            long totalSent = 0;
            int totalPending = 0;
            StringBuilder targets = new StringBuilder();

            for (ScanCoordinator coordinator : coordinators) {
                ScanCoordinator.Health health = coordinator.health();
                AgentConfig.ReaderConfig rc = coordinator.readerConfig();

                if (targets.length() > 0) {
                    targets.append(", ");
                }
                targets.append(health.readerTarget());

                allConnected = allConnected && health.readerConnected();
                anyRunning = anyRunning || health.inventoryRunning();
                if (firstActiveSession == null && health.activeSessionId() != null) {
                    firstActiveSession = health.activeSessionId();
                }
                totalUnique += health.unique();
                totalSent += health.sent();
                totalPending += health.pending();

                readerEntries.add(Json.object(
                        "readerId", rc.readerId().isEmpty() ? null : rc.readerId(),
                        "readerName", rc.readerName(),
                        "reader", health.readerTarget(),
                        "antennas", rc.readerAntennas(),
                        "readerConnected", health.readerConnected(),
                        "inventoryRunning", health.inventoryRunning(),
                        "activeSessionId", health.activeSessionId(),
                        "uniqueEpcs", health.unique(),
                        "sentEpcs", health.sent(),
                        "pendingEpcs", health.pending()
                ));
            }

            respond(exchange, 200, Json.object(
                    "status", "ok",
                    "reader", targets.toString(),
                    "readerConnected", allConnected,
                    "inventoryRunning", anyRunning,
                    "activeSessionId", firstActiveSession,
                    "uniqueEpcs", totalUnique,
                    "sentEpcs", totalSent,
                    "pendingEpcs", totalPending,
                    "readers", Json.array(readerEntries)
            ));
        } finally {
            exchange.close();
        }
    }

    private void handleUnknown(HttpExchange exchange) throws IOException {
        try {
            respond(exchange, 404, Json.object(
                    "error", "not_found",
                    "paths", "POST /scan/start, POST /scan/stop, GET /health"
            ));
        } finally {
            exchange.close();
        }
    }

    /**
     * The middleware sends no credentials to the agent. AGENT_API_KEY is an
     * opt in for shared networks: set it here and on the middleware side.
     */
    private boolean isAuthorised(HttpExchange exchange) {
        if (config.controlApiKey.isEmpty()) {
            return true;
        }

        List<String> provided = exchange.getRequestHeaders().get("x-api-key");

        return provided != null && provided.contains(config.controlApiKey);
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            byte[] bytes = input.readNBytes(MAX_BODY_BYTES);

            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);

        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }
}
