package dev.tkachuk.bank.paymentfile;

import java.math.BigDecimal;

/**
 * One payment inside a payment file. Values are as written in the file; the transfer validates them.
 *
 * @param remittanceInfo free text for the creditor, or null when the file has none
 */
public record PaymentInstruction(
        String endToEndId,
        String creditorIban,
        String creditorName,
        BigDecimal amount,
        String currency,
        String remittanceInfo) {
}
