package dev.tkachuk.bank.paymentfile;

import java.util.List;

public record PaymentBatchReport(String messageId, PaymentFileFormat format, List<PaymentItemOutcome> items) {

    public PaymentBatchReport {
        items = List.copyOf(items);
    }

    public int acceptedCount() {
        return (int) items.stream().filter(item -> item.status() == PaymentItemOutcome.Status.ACCEPTED).count();
    }

    public int rejectedCount() {
        return items.size() - acceptedCount();
    }
}
