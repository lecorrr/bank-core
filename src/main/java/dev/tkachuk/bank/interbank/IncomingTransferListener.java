package dev.tkachuk.bank.interbank;

import dev.tkachuk.bank.transfer.IncomingTransfers;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Credits or rejects payments from other banks. Invalid events are skipped; other failures are retried. */
@Component
class IncomingTransferListener {

    private static final Logger log = LoggerFactory.getLogger(IncomingTransferListener.class);

    private final IncomingTransfers incomingTransfers;

    IncomingTransferListener(IncomingTransfers incomingTransfers) {
        this.incomingTransfers = incomingTransfers;
    }

    @KafkaListener(topics = IncomingTransferReceived.TOPIC, groupId = TransferStatusListener.GROUP,
            containerFactory = InterbankConfiguration.LISTENER_FACTORY)
    void on(ConsumerRecord<String, String> record) {
        read(record).ifPresent(this::apply);
    }

    Optional<IncomingTransferReceived> read(ConsumerRecord<String, String> record) {
        return InterbankJson.read(record, IncomingTransferReceived.class,
                (where, problem) -> log.error("Skipping invalid payment event at {}, the payment gets no answer: {}",
                        where, problem));
    }

    void apply(IncomingTransferReceived event) {
        IncomingTransfers.Received received = incomingTransfers.receive(event.toCommand());
        UUID transferId = received.decision().transferId();
        switch (received.outcome()) {
            case CREDITED -> log.info("Payment {} from another bank: {} {} credited (transfer {})", event.uetr(),
                    event.amount().toPlainString(), event.currency(), transferId);
            case REJECTED -> log.info("Payment {} from another bank rejected: {} (transfer {})", event.uetr(),
                    received.decision().rejection(), transferId);
            case ALREADY_DECIDED -> log.info("Payment {} received again, resending the decision (transfer {})",
                    event.uetr(), transferId);
        }
    }
}
