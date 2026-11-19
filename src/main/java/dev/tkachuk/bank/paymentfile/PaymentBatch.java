package dev.tkachuk.bank.paymentfile;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record PaymentBatch(String messageId, String debtorIban, List<PaymentInstruction> payments) {

    public PaymentBatch {
        payments = List.copyOf(payments);
    }

    public List<String> repeatedEndToEndIds() {
        Set<String> seen = new HashSet<>();
        Set<String> repeated = new LinkedHashSet<>();
        for (PaymentInstruction payment : payments) {
            if (!seen.add(payment.endToEndId())) {
                repeated.add(payment.endToEndId());
            }
        }
        return List.copyOf(repeated);
    }
}
