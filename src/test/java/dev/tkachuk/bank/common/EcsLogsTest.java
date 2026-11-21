package dev.tkachuk.bank.common;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** The ECS JSON log lines as the apps write them in Docker, with the settings from application.yml. */
class EcsLogsTest {

    @Test
    void shouldWriteTraceAndRequestIdsUnderTheirEcsNames() throws IOException {
        String json = encode(Map.of("traceId", "0af7651916cd43dd8448eb211c80319c", "spanId", "b7ad6b7169203331",
                "requestId", "demo-request-1"));

        assertThat(JsonPath.<String>read(json, "$['trace.id']")).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(JsonPath.<String>read(json, "$['span.id']")).isEqualTo("b7ad6b7169203331");
        assertThat(JsonPath.<String>read(json, "$['http.request.id']")).isEqualTo("demo-request-1");
        assertThat(JsonPath.<String>read(json, "$.service.name")).isEqualTo("bank-core");
        assertThat(JsonPath.<String>read(json, "$.message")).isEqualTo("Transfer is SETTLED");
    }

    private static String encode(Map<String, String> mdc) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        LoggerContext logback = new LoggerContext();
        logback.putObject(Environment.class.getName(), environment);
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(logback);
        encoder.setFormat("ecs");
        encoder.start();

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName("dev.tkachuk.bank.interbank.TransferStatusListener");
        event.setLevel(Level.INFO);
        event.setThreadName("main");
        event.setMessage("Transfer is SETTLED");
        event.setInstant(Instant.parse("2026-11-19T10:00:00Z"));
        event.setMDCPropertyMap(mdc);
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }
}
