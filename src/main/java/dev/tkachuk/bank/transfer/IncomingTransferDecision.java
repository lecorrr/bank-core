package dev.tkachuk.bank.transfer;

import java.time.Instant;
import java.util.UUID;

/**
 * This bank's decision on a payment from another bank: credited to the payee, or refused with a reason.
 *
 * IncomingTransfers publishes it inside the transfer's own transaction, so it goes out through the outbox exactly when
 * the transfer exists, as InterbankTransferPending does; the interbank package decides how it looks on the wire. A
 * copy of the payment publishes the same decision again.
 *
 * @param transferId the transfer this bank made of the payment, credited or refused
 * @param rejection  why the payment was refused; null for one credited
 * @param decidedAt  when the transfer was made
 */
public record IncomingTransferDecision(UUID uetr, UUID transferId, IncomingTransferRejection rejection,
        Instant decidedAt) {

    static IncomingTransferDecision of(Transfer transfer) {
        return new IncomingTransferDecision(transfer.getUetr(), transfer.getId(), transfer.getRejectionReason(),
                transfer.getCreatedAt());
    }

    public boolean credited() {
        return rejection == null;
    }
}
