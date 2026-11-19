package dev.tkachuk.bank.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record IncomingTransferCommand(
        UUID uetr,
        String fromIban,
        String fromName,
        String toIban,
        String toName,
        BigDecimal amount,
        String currency,
        String description,
        Instant receivedAt) {
}
