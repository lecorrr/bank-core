package dev.tkachuk.bank.interbank;

import dev.tkachuk.bank.transfer.InterbankTransferPending;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A transfer to another bank, for bank-integration, keyed by the payer's IBAN. */
public record TransferRequested(
        UUID transferId,
        String fromIban,
        String fromName,
        String toIban,
        String toName,
        BigDecimal amount,
        String currency,
        String description,
        Instant requestedAt) {

    public static final String TOPIC = "interbank.transfers";

    static TransferRequested of(InterbankTransferPending pending) {
        return new TransferRequested(pending.transferId(), pending.fromIban(), pending.fromName(), pending.toIban(),
                pending.toName(), pending.amount(), pending.currency(), pending.description(), pending.requestedAt());
    }
}
