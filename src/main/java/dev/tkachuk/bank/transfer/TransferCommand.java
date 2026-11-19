package dev.tkachuk.bank.transfer;

import java.math.BigDecimal;

public record TransferCommand(
        String idempotencyKey,
        String fromIban,
        String toIban,
        String toName,
        BigDecimal amount,
        String currency,
        String description) {

    public TransferCommand(String idempotencyKey, String fromIban, String toIban, BigDecimal amount, String currency,
            String description) {
        this(idempotencyKey, fromIban, toIban, null, amount, currency, description);
    }
}
