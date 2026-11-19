package dev.tkachuk.bank.interbank;

import java.time.Instant;
import java.util.UUID;

/** The other bank's answer to a transfer, from bank-integration. reportId is the pacs.002 MsgId. */
public record TransferStatusChanged(UUID transferId, Status status, String reportId, Instant reportedAt) {

    public static final String TOPIC = "interbank.transfer-statuses";

    private static final int MAX_REPORT_ID_LENGTH = 35;

    public enum Status {
        SETTLED,
        REJECTED,
        PENDING
    }

    public TransferStatusChanged {
        if (transferId == null) {
            throw new IllegalArgumentException("transferId is missing");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is missing");
        }
        if (reportId == null || reportId.isBlank() || reportId.length() > MAX_REPORT_ID_LENGTH) {
            throw new IllegalArgumentException("reportId must have 1 to " + MAX_REPORT_ID_LENGTH + " characters");
        }
    }
}
