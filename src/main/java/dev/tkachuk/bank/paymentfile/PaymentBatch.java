package dev.tkachuk.bank.paymentfile;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A whole payment file: one debtor account paying every instruction in the list. */
public record PaymentBatch(String messageId, String debtorIban, List<PaymentInstruction> payments) {

    public PaymentBatch {
        payments = List.copyOf(payments);
    }

    /**
     * End-to-end ids that more than one payment uses, each listed once, in file order. A file with any is
     * refused: with the message id, the end-to-end id is each payment's idempotency key, so a repeated one
     * would make the second payment look like a repeat of the first.
     */
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
