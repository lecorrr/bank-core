package dev.tkachuk.bank.interbank;

import dev.tkachuk.bank.transfer.TransferSettlement;
import dev.tkachuk.bank.transfer.TransferSettlement.Outcome;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies the other banks' answers to bank-core's transfers. An event that cannot be read is logged and skipped:
 * no retry would make it readable. An answer that cannot be applied right now (the database is down, say) throws,
 * and the listener's error handler tries it again until it works (see InterbankConfiguration), so no answer is lost.
 */
@Component
class TransferStatusListener {

    /** bank-core's consumer group: it reads every status, whoever else does. */
    static final String GROUP = "bank-core";

    private static final Logger log = LoggerFactory.getLogger(TransferStatusListener.class);

    /** Its own mapper, as bank-integration has: fields bank-integration adds later are ignored, not an error. */
    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final TransferSettlement settlement;

    TransferStatusListener(TransferSettlement settlement) {
        this.settlement = settlement;
    }

    @KafkaListener(topics = TransferStatusChanged.TOPIC, groupId = GROUP,
            containerFactory = InterbankConfiguration.STATUS_LISTENER_FACTORY)
    void on(ConsumerRecord<String, String> record) {
        read(record).ifPresent(this::apply);
    }

    /** The event, or empty (logged) when it is not one bank-core can apply. */
    Optional<TransferStatusChanged> read(ConsumerRecord<String, String> record) {
        String where = record.topic() + "-" + record.partition() + "@" + record.offset();
        if (record.value() == null || record.value().isBlank()) {
            log.warn("Skipped an empty status event at {}", where);
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(record.value(), TransferStatusChanged.class));
        } catch (JacksonException e) {
            // Jackson wraps what the record's own checks throw, so this covers both bad JSON and bad values.
            log.warn("Skipped a status event at {} that is not valid: {}", where, reasonOf(e));
            return Optional.empty();
        }
    }

    void apply(TransferStatusChanged event) {
        switch (event.status()) {
            case SETTLED -> report(event, settlement.settle(event.transferId(), event.reportId()));
            case REJECTED -> report(event, settlement.reject(event.transferId(), event.reportId()));
            case PENDING -> log.info("Transfer {} is still pending at the other bank (pacs.002 {})",
                    event.transferId(), event.reportId());
        }
    }

    private static void report(TransferStatusChanged event, Outcome outcome) {
        switch (outcome) {
            case APPLIED -> log.info("Transfer {} is {} (pacs.002 {})", event.transferId(), event.status(),
                    event.reportId());
            case ALREADY_APPLIED -> log.info("Transfer {} was already {}: repeated answer ignored (pacs.002 {})",
                    event.transferId(), event.status(), event.reportId());
            case CONFLICT -> log.error("Transfer {} already has the opposite answer, so {} from pacs.002 {} was not "
                    + "applied: check it by hand", event.transferId(), event.status(), event.reportId());
            case UNKNOWN_TRANSFER -> log.warn("There is no transfer {}: {} from pacs.002 {} ignored",
                    event.transferId(), event.status(), event.reportId());
        }
    }

    /** A record's own check says what is wrong more plainly than the parser's message around it. */
    private static String reasonOf(Exception e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException) {
                return cause.getMessage();
            }
        }
        return "not a " + TransferStatusChanged.class.getSimpleName() + " (" + e.getClass().getSimpleName() + ")";
    }
}
