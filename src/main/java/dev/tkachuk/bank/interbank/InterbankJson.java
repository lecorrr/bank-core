package dev.tkachuk.bank.interbank;

import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the events bank-integration publishes, which arrive as JSON text. Its own mapper, as bank-integration has:
 * fields bank-integration adds later are ignored, not an error (a tolerant reader).
 */
final class InterbankJson {

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private InterbankJson() {
    }

    /**
     * The event, or empty when the record holds none that bank-core could act on: no text, text that is not JSON,
     * or JSON that breaks one of the event's own checks. The problem goes to the consumer, which logs it.
     */
    static <T> Optional<T> read(ConsumerRecord<String, String> record, Class<T> type, ProblemLog problems) {
        String where = whereIs(record);
        if (record.value() == null || record.value().isBlank()) {
            problems.log(where, "the event is empty");
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(record.value(), type));
        } catch (JacksonException e) {
            // Jackson wraps what the record's own checks throw, so this covers both bad JSON and bad values.
            problems.log(where, reasonOf(e, type));
            return Optional.empty();
        }
    }

    /** Where the record sits, topic-partition@offset, so a person can find it. */
    static String whereIs(ConsumerRecord<?, ?> record) {
        return record.topic() + "-" + record.partition() + "@" + record.offset();
    }

    /** A record's own check says what is wrong more plainly than the parser's message around it. */
    private static String reasonOf(Exception e, Class<?> type) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException) {
                return cause.getMessage();
            }
        }
        return "not a " + type.getSimpleName() + " (" + e.getClass().getSimpleName() + ")";
    }

    /** How a consumer logs an event it skips: the place, and the problem. */
    @FunctionalInterface
    interface ProblemLog {
        void log(String where, String problem);
    }
}
