package dev.tkachuk.bank.interbank;

import dev.tkachuk.bank.transfer.IncomingTransferDecision;
import java.time.Instant;
import java.util.UUID;

/**
 * The event bank-integration turns into its answer to the other bank, a pacs.002: ACSC for a payment credited, RJCT
 * with an ISO reason code for one refused. The same fields as bank-integration's own IncomingTransferDecided; the
 * record is kept apart from the internal IncomingTransferDecision so that the contract changes only when both sides
 * agree.
 *
 * @param transferId the transfer bank-core made of the payment, credited or refused
 * @param reason     why it was refused, in bank-core's words (UNKNOWN_ACCOUNT, CURRENCY_MISMATCH); null when
 *                   credited
 */
public record IncomingTransferDecided(UUID uetr, UUID transferId, Decision decision, String reason,
        Instant decidedAt) {

    /** Keyed by the UETR, so the decisions about one payment keep their order. */
    public static final String TOPIC = "interbank.incoming-transfer-decisions";

    public enum Decision {
        CREDITED,
        REJECTED
    }

    static IncomingTransferDecided of(IncomingTransferDecision decision) {
        return decision.credited()
                ? new IncomingTransferDecided(decision.uetr(), decision.transferId(), Decision.CREDITED, null,
                        decision.decidedAt())
                : new IncomingTransferDecided(decision.uetr(), decision.transferId(), Decision.REJECTED,
                        decision.rejection().name(), decision.decidedAt());
    }
}
