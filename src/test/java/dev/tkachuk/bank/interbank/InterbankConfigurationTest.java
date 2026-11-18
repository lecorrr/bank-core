package dev.tkachuk.bank.interbank;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.bank.interbank.IncomingTransferDecided.Decision;
import dev.tkachuk.bank.support.TestIbans;
import dev.tkachuk.bank.transfer.IncomingTransferDecision;
import dev.tkachuk.bank.transfer.IncomingTransferRejection;
import dev.tkachuk.bank.transfer.InterbankTransferPending;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;

/** Which events leave bank-core, in which wire form, to which topic and under which key. */
class InterbankConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-11-18T09:15:02Z");
    private static final UUID UETR = UUID.fromString("3b9f7a52-1c4d-4e8f-a0b6-7d2e5c9f1a34");
    private static final UUID TRANSFER_ID = UUID.fromString("9d1c4b7e-2f3a-4e5b-8c6d-0a1b2c3d4e5f");

    private final EventExternalizationConfiguration externalization =
            new InterbankConfiguration().eventExternalizationConfiguration();

    @Test
    void aTransferToAnotherBankGoesOutAsTransferRequestedKeyedByThePayer() {
        InterbankTransferPending pending = new InterbankTransferPending(TRANSFER_ID, TestIbans.A, "Artem Demo",
                TestIbans.PARTNER, "Olena Kovalenko", new BigDecimal("150.00"), "UAH", "Rent", NOW);

        assertThat(externalization.supports(pending)).isTrue();
        assertThat(externalization.map(pending)).isEqualTo(TransferRequested.of(pending));
        RoutingTarget target = externalization.determineTarget(pending);
        assertThat(target.getTarget()).isEqualTo(TransferRequested.TOPIC);
        assertThat(target.getKey()).isEqualTo(TestIbans.A);
    }

    @Test
    void aDecisionGoesOutAsIncomingTransferDecidedKeyedByTheUetr() {
        IncomingTransferDecision decision = new IncomingTransferDecision(UETR, TRANSFER_ID,
                IncomingTransferRejection.CURRENCY_MISMATCH, NOW);

        assertThat(externalization.supports(decision)).isTrue();
        assertThat(externalization.map(decision)).isEqualTo(new IncomingTransferDecided(UETR, TRANSFER_ID,
                Decision.REJECTED, "CURRENCY_MISMATCH", NOW));
        RoutingTarget target = externalization.determineTarget(decision);
        assertThat(target.getTarget()).isEqualTo(IncomingTransferDecided.TOPIC);
        assertThat(target.getKey()).isEqualTo(UETR.toString());
    }

    @Test
    void aPaymentCreditedIsDecidedWithoutAReason() {
        IncomingTransferDecision credited = new IncomingTransferDecision(UETR, TRANSFER_ID, null, NOW);

        assertThat(externalization.map(credited)).isEqualTo(new IncomingTransferDecided(UETR, TRANSFER_ID,
                Decision.CREDITED, null, NOW));
    }

    @Test
    void nothingElseLeavesBankCore() {
        assertThat(externalization.supports("an event of some other kind")).isFalse();
    }
}
