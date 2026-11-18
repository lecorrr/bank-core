package dev.tkachuk.bank.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment from a customer of another bank to a customer of this one, as bank-integration brought it in. Nothing
 * in it is validated yet: IncomingTransfers checks the IBANs, the amount and the payee's account.
 *
 * @param uetr       the unique end-to-end reference the other bank gave the payment, by which a copy is recognised
 * @param receivedAt when this bank received the payment
 */
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
