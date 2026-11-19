package dev.tkachuk.bank.interbank;

import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Reads incoming events with its own mapper; unknown fields are ignored. */
final class InterbankJson {

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private InterbankJson() {
    }

    static <T> Optional<T> read(ConsumerRecord<String, String> record, Class<T> type, ProblemLog problems) {
        String where = whereIs(record);
        if (record.value() == null || record.value().isBlank()) {
            problems.log(where, "empty event");
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(record.value(), type));
        } catch (JacksonException e) {
            problems.log(where, reasonOf(e, type));
            return Optional.empty();
        }
    }

    static String whereIs(ConsumerRecord<?, ?> record) {
        return record.topic() + "-" + record.partition() + "@" + record.offset();
    }

    // The record's own check says what's wrong more plainly than Jackson's wrapper.
    private static String reasonOf(Exception e, Class<?> type) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException) {
                return cause.getMessage();
            }
        }
        return "invalid " + type.getSimpleName() + " (" + e.getClass().getSimpleName() + ")";
    }

    @FunctionalInterface
    interface ProblemLog {
        void log(String where, String problem);
    }
}
