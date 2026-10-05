package com.geoplan.rfid.agent.command;

import com.geoplan.rfid.agent.config.AgentConfig;
import com.geoplan.rfid.agent.scan.ScanCoordinator;
import com.geoplan.rfid.agent.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Outbound HTTPS client for long-polling and acknowledging reader commands. */
public final class ReaderCommandClient {

    private final AgentConfig config;
    private final HttpClient httpClient;

    public ReaderCommandClient(AgentConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.middlewareTimeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public ReaderCommand poll(ScanCoordinator.Health health) throws IOException, InterruptedException {
        String body = Json.object(
                "readerId", config.readerId,
                "readerConnected", health.readerConnected(),
                "inventoryRunning", health.inventoryRunning(),
                "activeSessionId", health.activeSessionId()
        );
        HttpResponse<String> response = httpClient.send(
                request(config.commandPollUrl(), config.commandPollRequestTimeoutMs)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Command poll returned HTTP " + response.statusCode()
                    + ": " + snippet(response.body()));
        }

        String commandId = Json.readString(response.body(), "commandId");
        if (commandId == null || commandId.isBlank()) {
            return null;
        }
        String type = Json.readString(response.body(), "type");
        String sessionId = Json.readString(response.body(), "sessionId");
        if (type == null || sessionId == null) {
            throw new IOException("Command poll returned an incomplete command: " + snippet(response.body()));
        }

        try {
            return new ReaderCommand(commandId, ReaderCommand.Type.valueOf(type), sessionId);
        } catch (IllegalArgumentException error) {
            throw new IOException("Command poll returned unsupported type " + type, error);
        }
    }

    public void acknowledge(
            ReaderCommand command,
            boolean succeeded,
            String error,
            ScanCoordinator.Health health
    ) throws IOException, InterruptedException {
        String body = Json.object(
                "readerId", config.readerId,
                "status", succeeded ? "SUCCEEDED" : "FAILED",
                "readerConnected", health.readerConnected(),
                "inventoryRunning", health.inventoryRunning(),
                "activeSessionId", health.activeSessionId(),
                "error", error
        );
        HttpResponse<String> response = httpClient.send(
                request(config.commandAckUrl(command.commandId()), config.middlewareTimeoutMs)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Command acknowledgement returned HTTP " + response.statusCode()
                    + ": " + snippet(response.body()));
        }
    }

    private HttpRequest.Builder request(String url, long timeoutMs) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (!config.middlewareApiKey.isEmpty()) {
            request.header("x-api-key", config.middlewareApiKey);
        }
        return request;
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "<empty body>";
        }
        String flattened = body.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 200 ? flattened : flattened.substring(0, 200) + "...";
    }
}
