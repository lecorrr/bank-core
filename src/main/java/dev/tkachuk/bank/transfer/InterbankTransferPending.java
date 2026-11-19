package dev.tkachuk.bank.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
