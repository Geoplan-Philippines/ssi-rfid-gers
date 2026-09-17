package com.geoplan.rfid.agent.middleware;

import com.geoplan.rfid.agent.config.AgentConfig;
import com.geoplan.rfid.agent.util.Json;
import com.geoplan.rfid.agent.util.Log;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Appends EPC batches to the middleware.
 *
 * POST {MIDDLEWARE_BASE_URL}/api/v1/epc-scan-processing/sessions/{sessionId}/reads
 * Body   {"epcs":["E280..."]}   1..1000 entries
 * Header x-api-key
 */
public final class MiddlewareClient {

    private final AgentConfig config;
    private final HttpClient httpClient;

    public MiddlewareClient(AgentConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.middlewareTimeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public AppendOutcome appendReads(String sessionId, List<String> epcs) {
        if (epcs.isEmpty()) {
            return AppendOutcome.SENT;
        }

        String url = config.readsUrl(sessionId);
        String body = Json.stringArrayBody("epcs", epcs);

        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(config.middlewareTimeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));

            if (!config.middlewareApiKey.isEmpty()) {
                request.header("x-api-key", config.middlewareApiKey);
            }

            HttpResponse<String> response = httpClient.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            return classify(sessionId, epcs.size(), response);
        } catch (Exception e) {
            Log.warn("Append failed for session " + sessionId + " (" + epcs.size() + " EPCs): "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return AppendOutcome.RETRY;
        }
    }

    private AppendOutcome classify(String sessionId, int count, HttpResponse<String> response) {
        int status = response.statusCode();

        if (status >= 200 && status < 300) {
            Log.info("Appended " + count + " EPCs to session " + sessionId + " (HTTP " + status + ")");
            return AppendOutcome.SENT;
        }

        String detail = "session " + sessionId + " | HTTP " + status + " | " + snippet(response.body());

        /* The session is gone, cancelled or already completed. Nothing to append to. */
        if (status == 404 || status == 409 || status == 410) {
            Log.warn("Middleware refused reads, stopping session: " + detail);
            return AppendOutcome.SESSION_CLOSED;
        }

        /* Bad payload. Retrying the same body would loop forever. */
        if (status == 400 || status == 422) {
            Log.error("Middleware rejected the batch, dropping " + count + " EPCs: " + detail);
            return AppendOutcome.DROPPED;
        }

        if (status == 401 || status == 403) {
            Log.error("Middleware rejected the API key, will retry: " + detail);
            return AppendOutcome.RETRY;
        }

        Log.warn("Append failed, will retry: " + detail);
        return AppendOutcome.RETRY;
    }

    private static String snippet(String body) {
        if (body == null || body.isEmpty()) {
            return "<empty body>";
        }

        String flattened = body.replaceAll("\\s+", " ").trim();

        return flattened.length() <= 200 ? flattened : flattened.substring(0, 200) + "...";
    }
}
