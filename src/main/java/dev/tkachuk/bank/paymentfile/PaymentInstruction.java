package dev.tkachuk.bank.paymentfile;

import java.math.BigDecimal;

public record PaymentInstruction(
        String endToEndId,
        String creditorIban,
        String creditorName,
        BigDecimal amount,
        String currency,
        String remittanceInfo) {
}
