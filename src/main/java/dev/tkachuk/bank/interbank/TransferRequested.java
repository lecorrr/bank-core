package dev.tkachuk.bank.interbank;

import dev.tkachuk.bank.transfer.InterbankTransferPending;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The event bank-integration turns into a pacs.008 for the other bank. Its contract is the JSON these components
 * make, the same fields as bank-integration's own TransferRequested; the record is kept apart from the internal
 * InterbankTransferPending so that the contract changes only when both sides agree.
 */
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

    /** Keyed by the payer's IBAN, so one account's transfers keep their order. */
    public static final String TOPIC = "interbank.transfers";

    static TransferRequested of(InterbankTransferPending pending) {
        return new TransferRequested(pending.transferId(), pending.fromIban(), pending.fromName(), pending.toIban(),
                pending.toName(), pending.amount(), pending.currency(), pending.description(), pending.requestedAt());
    }
}
