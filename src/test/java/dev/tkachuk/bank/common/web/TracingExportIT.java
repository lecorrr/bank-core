package dev.tkachuk.bank.common.web;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import dev.tkachuk.bank.support.IntegrationTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * With an OTLP endpoint, as the Helm chart gives one, bank-core sends its spans there: a request's span arrives under
 * the service's name and in OpenTelemetry's HTTP names, the ones the trace collector keeps. The JDK's HTTP server
 * stands in for the collector. Its own context, closed after the class, so no exporter is left sending to it.
 */
@DirtiesContext
class TracingExportIT extends IntegrationTest {

    private static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer COLLECTOR = startCollector();

    private static HttpServer startCollector() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/v1/traces", exchange -> {
                // OTLP/HTTP sends protobuf, in which names and values are plain bytes: ISO-8859-1 keeps each one.
                RECEIVED.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Before the context starts: Spring Boot decides on the exporter while it reads the configuration.
    @DynamicPropertySource
    static void collector(DynamicPropertyRegistry registry) {
        registry.add("management.opentelemetry.tracing.export.otlp.endpoint",
                () -> "http://127.0.0.1:" + COLLECTOR.getAddress().getPort() + "/v1/traces");
        // A batch every 100 ms rather than every 5 s.
        registry.add("management.opentelemetry.tracing.export.schedule-delay", () -> "100ms");
    }

    @AfterAll
    static void stopCollector() {
        COLLECTOR.stop(0);
    }

    @Test
    void shouldSendARequestsSpanToTheCollectorInOpenTelemetrysNames() throws Exception {
        mockMvc.perform(get("/api/v1/accounts")).andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(15)).until(() -> RECEIVED.stream().anyMatch(body ->
                body.contains("bank-core") && body.contains("http.route") && body.contains("/api/v1/accounts")));
    }
}
