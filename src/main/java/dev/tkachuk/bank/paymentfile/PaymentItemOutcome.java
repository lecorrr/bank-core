package dev.tkachuk.bank.paymentfile;

import dev.tkachuk.bank.common.error.ErrorCode;
import java.util.UUID;

public record PaymentItemOutcome(
        String endToEndId,
        Status status,
        UUID transferId,
        boolean replayed,
        ErrorCode errorCode,
        String reason) {

    public enum Status {
        ACCEPTED,
        REJECTED
    }

    static PaymentItemOutcome ofAccepted(String endToEndId, UUID transferId, boolean replayed) {
        return new PaymentItemOutcome(endToEndId, Status.ACCEPTED, transferId, replayed, null, null);
    }

    static PaymentItemOutcome ofRejected(String endToEndId, ErrorCode errorCode, String reason) {
        return new PaymentItemOutcome(endToEndId, Status.REJECTED, null, false, errorCode, reason);
    }
}
