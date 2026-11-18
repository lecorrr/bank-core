package dev.tkachuk.bank.interbank;

import java.time.Instant;
import java.util.UUID;

/**
 * The event bank-integration publishes when the other bank answers a transfer with a pacs.002. Read from JSON by
 * TransferStatusListener; the checks below reject an event that could not be applied, so it is skipped.
 *
 * @param reportId   the MsgId of the other bank's pacs.002, kept with the transfer (at most 35 characters, as
 *                   ISO 20022 allows)
 * @param reportedAt when the other bank wrote its answer
 */
public record TransferStatusChanged(UUID transferId, Status status, String reportId, Instant reportedAt) {

    /** Keyed by the transfer id. */
    public static final String TOPIC = "interbank.transfer-statuses";

    private static final int MAX_REPORT_ID_LENGTH = 35;

    /** The other bank's answer: ACSC, RJCT or PDNG in ISO 20022 terms. */
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
