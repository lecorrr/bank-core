package dev.tkachuk.bank.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A transfer to another bank has taken the money from the customer and waits for that bank's answer.
 *
 * TransferService publishes it inside the transfer's own transaction. Spring Modulith stores it in the
 * event_publication table in that transaction and sends it to Kafka after the commit, so the event exists exactly
 * when the transfer does (the transactional outbox). The interbank package decides how it looks on the wire.
 *
 * @param fromName    the payer's name, as the account holds it
 * @param requestedAt when the transfer was made
 */
public record InterbankTransferPending(
        UUID transferId,
        String fromIban,
        String fromName,
        String toIban,
        String toName,
        BigDecimal amount,
        String currency,
        String description,
        Instant requestedAt) {

    static InterbankTransferPending of(Transfer transfer, String fromName) {
        return new InterbankTransferPending(transfer.getId(), transfer.getFromIban(), fromName,
                transfer.getToIban(), transfer.getToName(), transfer.getAmount(), transfer.getCurrency(),
                transfer.getDescription(), transfer.getCreatedAt());
    }
}
