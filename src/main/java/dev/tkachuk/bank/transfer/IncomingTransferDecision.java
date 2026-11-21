package dev.tkachuk.bank.transfer;

import java.time.Instant;
import java.util.UUID;

/** Our answer to another bank's payment; traceParent keeps the trace of the payment event for Kafka. */
public record IncomingTransferDecision(UUID uetr, UUID transferId, IncomingTransferRejection rejection,
        Instant decidedAt, String traceParent) {

    static IncomingTransferDecision of(Transfer transfer, String traceParent) {
        return new IncomingTransferDecision(transfer.getUetr(), transfer.getId(), transfer.getRejectionReason(),
                transfer.getCreatedAt(), traceParent);
    }

    public boolean credited() {
        return rejection == null;
    }
}
