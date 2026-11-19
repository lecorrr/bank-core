package dev.tkachuk.bank.interbank;

import dev.tkachuk.bank.transfer.TransferSettlement;
import dev.tkachuk.bank.transfer.TransferSettlement.Outcome;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Applies the other banks' answers. Invalid events are skipped; other failures are retried. */
@Component
class TransferStatusListener {

    static final String GROUP = "bank-core";

    private static final Logger log = LoggerFactory.getLogger(TransferStatusListener.class);

    private final TransferSettlement settlement;

    TransferStatusListener(TransferSettlement settlement) {
        this.settlement = settlement;
    }

    @KafkaListener(topics = TransferStatusChanged.TOPIC, groupId = GROUP,
            containerFactory = InterbankConfiguration.LISTENER_FACTORY)
    void on(ConsumerRecord<String, String> record) {
        read(record).ifPresent(this::apply);
    }

    Optional<TransferStatusChanged> read(ConsumerRecord<String, String> record) {
        return InterbankJson.read(record, TransferStatusChanged.class,
                (where, problem) -> log.warn("Skipping invalid status event at {}: {}", where, problem));
    }

    void apply(TransferStatusChanged event) {
        switch (event.status()) {
            case SETTLED -> report(event, settlement.settle(event.transferId(), event.reportId()));
            case REJECTED -> report(event, settlement.reject(event.transferId(), event.reportId()));
            case PENDING -> log.info("Transfer {} still pending at the other bank (pacs.002 {})",
                    event.transferId(), event.reportId());
        }
    }

    private static void report(TransferStatusChanged event, Outcome outcome) {
        switch (outcome) {
            case APPLIED -> log.info("Transfer {} is {} (pacs.002 {})", event.transferId(), event.status(),
                    event.reportId());
            case ALREADY_APPLIED -> log.info("Transfer {} already {}, ignoring repeated answer (pacs.002 {})",
                    event.transferId(), event.status(), event.reportId());
            case CONFLICT -> log.error("Transfer {} already has the opposite answer, ignoring {} from pacs.002 {}",
                    event.transferId(), event.status(), event.reportId());
            case UNKNOWN_TRANSFER -> log.warn("Unknown transfer {}, ignoring {} from pacs.002 {}",
                    event.transferId(), event.status(), event.reportId());
        }
    }
}
