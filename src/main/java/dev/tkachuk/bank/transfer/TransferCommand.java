package dev.tkachuk.bank.transfer;

import java.math.BigDecimal;

/**
 * A request to move money, as it arrived (from the API or a payment file). Nothing in it is validated yet:
 * TransferService checks the IBANs, the amount and the accounts.
 *
 * @param toName the payee's name: required for a transfer to another bank, optional within this bank
 */
public record TransferCommand(
        String idempotencyKey,
        String fromIban,
        String toIban,
        String toName,
        BigDecimal amount,
        String currency,
        String description) {

    /** A transfer without the payee's name, which is enough within this bank. */
    public TransferCommand(String idempotencyKey, String fromIban, String toIban, BigDecimal amount, String currency,
            String description) {
        this(idempotencyKey, fromIban, toIban, null, amount, currency, description);
    }
}
