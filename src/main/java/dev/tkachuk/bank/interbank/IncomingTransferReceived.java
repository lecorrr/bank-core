package dev.tkachuk.bank.interbank;

import dev.tkachuk.bank.transfer.IncomingTransferCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The event bank-integration publishes for a payment from another bank to a customer of this one, the same fields
 * as bank-integration's own IncomingTransferReceived. Read from JSON by IncomingTransferListener; the checks below
 * reject an event that could not be decided on as it is, so it is skipped rather than tried again for ever.
 * Whether the payee's account exists is not such a check: a payment to an unknown account is refused, and the
 * other bank hears why.
 *
 * @param receivedAt when bank-integration received the payment
 */
public record IncomingTransferReceived(
        UUID uetr,
        String fromIban,
        String fromName,
        String toIban,
        String toName,
        BigDecimal amount,
        String currency,
        String description,
        Instant receivedAt) {

    /** Keyed by the payee's IBAN, so the payments to one account keep their order. */
    public static final String TOPIC = "interbank.incoming-transfers";

    /** ISO 20022's Max140Text, which the names and the description came in. */
    private static final int MAX_TEXT = 140;
    /** The longest IBAN there is. */
    private static final int MAX_IBAN = 34;
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public IncomingTransferReceived {
        if (uetr == null) {
            throw new IllegalArgumentException("uetr is missing");
        }
        requireText("fromIban", fromIban, MAX_IBAN);
        requireText("fromName", fromName, MAX_TEXT);
        requireText("toIban", toIban, MAX_IBAN);
        requireText("toName", toName, MAX_TEXT);
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("amount must be greater than zero, with at most two decimals");
        }
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be three capital letters, such as UAH");
        }
        requireText("description", description, MAX_TEXT);
        if (receivedAt == null) {
            throw new IllegalArgumentException("receivedAt is missing");
        }
    }

    IncomingTransferCommand toCommand() {
        return new IncomingTransferCommand(uetr, fromIban, fromName, toIban, toName, amount, currency, description,
                receivedAt);
    }

    /** Counted in code points, as PostgreSQL counts characters. */
    private static void requireText(String field, String value, int maxLength) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(field + " must have 1 to " + maxLength + " characters");
        }
    }
}
