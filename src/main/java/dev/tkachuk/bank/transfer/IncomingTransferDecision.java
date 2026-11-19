package dev.tkachuk.bank.transfer;

import java.time.Instant;
import java.util.UUID;

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
