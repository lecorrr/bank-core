package dev.tkachuk.bank.interbank;

import dev.tkachuk.bank.transfer.IncomingTransfers;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Credits the payments that customers of other banks send to this bank's customers, or refuses them; either way the
 * decision goes back to bank-integration as an IncomingTransferDecided event (InterbankConfiguration). A payment that
 * cannot be decided right now (the database is down, say) throws, and the listener's error handler tries it again
 * until it works, so no payment is lost.
 *
 * An event that cannot be read is logged as an error and skipped: no retry would make it readable. Its payment then
 * has no answer, and the other bank keeps asking about it, so a person has to look.
 */
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

    /** The event, or empty (logged) when it is not one bank-core can decide on. */
    Optional<IncomingTransferReceived> read(ConsumerRecord<String, String> record) {
        return InterbankJson.read(record, IncomingTransferReceived.class,
                (where, problem) -> log.error("Skipped a payment event at {} that is not valid, so the payment has no "
                        + "answer; check it by hand: {}", where, problem));
    }

    void apply(IncomingTransferReceived event) {
        IncomingTransfers.Received received = incomingTransfers.receive(event.toCommand());
        UUID transferId = received.decision().transferId();
        switch (received.outcome()) {
            case CREDITED -> log.info("Payment {} from another bank: {} {} credited (transfer {})", event.uetr(),
                    event.amount().toPlainString(), event.currency(), transferId);
            case REJECTED -> log.info("Payment {} from another bank: refused, {} (transfer {})", event.uetr(),
                    received.decision().rejection(), transferId);
            case ALREADY_DECIDED -> log.info("Payment {} came again: decided already (transfer {}), the decision "
                    + "goes out again", event.uetr(), transferId);
        }
    }
}
