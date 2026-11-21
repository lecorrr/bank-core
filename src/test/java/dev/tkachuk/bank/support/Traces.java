package dev.tkachuk.bank.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.test.system.CapturedOutput;

/** W3C traceparent values for tests, and the log lines a test captured. */
public final class Traces {

    public static final String TRACE_PARENT = "traceparent";

    private Traces() {
    }

    /** A traceparent as another service would send it: version 00, sampled. */
    public static String traceParent(String traceId, String spanId) {
        return "00-" + traceId + "-" + spanId + "-01";
    }

    public static String traceIdOf(String traceParent) {
        assertThat(traceParent).as("traceparent").matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
        return traceParent.split("-")[1];
    }

    public static String spanIdOf(String traceParent) {
        traceIdOf(traceParent);
        return traceParent.split("-")[2];
    }

    /** Waits for the log line that contains the text: plain text locally, ECS JSON in Docker. */
    public static String awaitLogLine(CapturedOutput output, String text, Duration timeout) {
        await().atMost(timeout).until(() -> lineWith(output, text).isPresent());
        return lineWith(output, text).orElseThrow();
    }

    private static Optional<String> lineWith(CapturedOutput output, String text) {
        return output.getOut().lines().filter(line -> line.contains(text)).findFirst();
    }
}
