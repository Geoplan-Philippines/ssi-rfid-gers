import com.geoplan.rfid.agent.config.AgentConfig;
import com.geoplan.rfid.agent.middleware.AppendOutcome;
import com.geoplan.rfid.agent.middleware.MiddlewareClient;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the same config and HTTP client used by an IntelliJ launch. */
public final class AgentEnvHeaderTest {
    public static void main(String[] args) throws Exception {
        String expectedKey = Boolean.getBoolean("TEST_NO_KEY") ? "" : args[0];
        AtomicReference<String> receivedKey = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/epc-scan-processing/sessions/test-session/reads", exchange -> {
            String key = exchange.getRequestHeaders().getFirst("x-api-key");
            receivedKey.set(key);
            boolean authorized = expectedKey.equals(key);
            byte[] body = (authorized ? "{}" :
                    "{\"statusCode\":401,\"message\":\"Provide an x-api-key header\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(authorized ? 200 : 401, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            System.setProperty("MIDDLEWARE_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort());
            AgentConfig config = AgentConfig.load();
            AppendOutcome outcome = new MiddlewareClient(config)
                    .appendReads("test-session", List.of("E280117000000001"));
            if (expectedKey.isEmpty()) {
                if (receivedKey.get() != null || outcome != AppendOutcome.RETRY) {
                    throw new AssertionError("Missing key must omit the header and receive HTTP 401");
                }
            } else if (!expectedKey.equals(receivedKey.get()) || outcome != AppendOutcome.SENT) {
                throw new AssertionError("Configured API key did not reach x-api-key; append result=" + outcome);
            }
            System.out.println("PASS: " + args[1]);
        } finally {
            server.stop(0);
        }
    }
}
