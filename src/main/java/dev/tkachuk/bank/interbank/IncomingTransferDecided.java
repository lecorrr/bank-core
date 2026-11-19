package dev.tkachuk.bank.interbank;

import dev.tkachuk.bank.transfer.IncomingTransferDecision;
import java.time.Instant;
import java.util.UUID;

/** Our decision on a payment from another bank, for bank-integration, keyed by UETR. */
public record IncomingTransferDecided(UUID uetr, UUID transferId, Decision decision, String reason,
        Instant decidedAt) {

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
