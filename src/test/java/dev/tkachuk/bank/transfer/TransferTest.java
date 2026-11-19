package dev.tkachuk.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.bank.support.TestIbans;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TransferTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final UUID UETR = UUID.fromString("3b9f7a52-1c4d-4e8f-a0b6-7d2e5c9f1a34");

    private static final TransferCommand ORIGINAL = new TransferCommand("key-1", TestIbans.A, TestIbans.B,
            new BigDecimal("150.00"), "UAH", "Rent");

    private static final TransferCommand TO_PARTNER = new TransferCommand("key-2", TestIbans.A, TestIbans.PARTNER,
            "Olena Kovalenko", new BigDecimal("150.00"), "UAH", "Rent");

    private final Transfer transfer = Transfer.completed(ORIGINAL, NOW);

    @Test
    void shouldMatchSamePayload() {
        assertThat(transfer.hasSamePayloadAs(ORIGINAL)).isTrue();
    }

    @Test
    void shouldCompareAmountsByValue() {
        TransferCommand sameAmountOneDecimal = new TransferCommand("key-1", TestIbans.A, TestIbans.B,
                new BigDecimal("150.0"), "UAH", "Rent");

        assertThat(transfer.hasSamePayloadAs(sameAmountOneDecimal)).isTrue();
    }

    @ParameterizedTest(name = "different {0}")
    @MethodSource("changedRequests")
    void shouldNotMatchDifferentPayload(String changed, TransferCommand command) {
        assertThat(transfer.hasSamePayloadAs(command)).isFalse();
    }

    static Stream<Arguments> changedRequests() {
        return Stream.of(
                Arguments.of("from account", new TransferCommand("key-1", TestIbans.C, TestIbans.B,
                        new BigDecimal("150.00"), "UAH", "Rent")),
                Arguments.of("to account", new TransferCommand("key-1", TestIbans.A, TestIbans.C,
                        new BigDecimal("150.00"), "UAH", "Rent")),
                Arguments.of("payee name", new TransferCommand("key-1", TestIbans.A, TestIbans.B,
                        "Olena Kovalenko", new BigDecimal("150.00"), "UAH", "Rent")),
                Arguments.of("amount", new TransferCommand("key-1", TestIbans.A, TestIbans.B,
                        new BigDecimal("150.01"), "UAH", "Rent")),
                Arguments.of("currency", new TransferCommand("key-1", TestIbans.A, TestIbans.B,
                        new BigDecimal("150.00"), "USD", "Rent")),
                Arguments.of("description", new TransferCommand("key-1", TestIbans.A, TestIbans.B,
                        new BigDecimal("150.00"), "UAH", "Rent for May")));
    }

    @Test
    void shouldBePendingWhenToAnotherBank() {
        Transfer pending = Transfer.pending(TO_PARTNER, NOW);

        assertThat(pending.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(pending.isPending()).isTrue();
        assertThat(pending.getToName()).isEqualTo("Olena Kovalenko");
        assertThat(pending.getStatusReportId()).isNull();
        assertThat(pending.hasSamePayloadAs(TO_PARTNER)).isTrue();
    }

    @Test
    void shouldCompleteWithReportId() {
        Transfer pending = Transfer.pending(TO_PARTNER, NOW);

        pending.settle("REPORT-1");

        assertThat(pending.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(pending.getStatusReportId()).isEqualTo("REPORT-1");
    }

    @Test
    void shouldRejectWithReportId() {
        Transfer pending = Transfer.pending(TO_PARTNER, NOW);

        pending.reject("REPORT-1");

        assertThat(pending.getStatus()).isEqualTo(TransferStatus.REJECTED);
        assertThat(pending.getStatusReportId()).isEqualTo("REPORT-1");
    }

    @Test
    void shouldAcceptOnlyOneAnswer() {
        Transfer settled = Transfer.pending(TO_PARTNER, NOW);
        settled.settle("REPORT-1");

        assertThatThrownBy(() -> settled.reject("REPORT-2")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> settled.settle("REPORT-2")).isInstanceOf(IllegalStateException.class);
        assertThat(settled.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(settled.getStatusReportId()).isEqualTo("REPORT-1");
    }

    @Test
    void shouldKeepIncomingPaymentByUetr() {
        IncomingTransferCommand payment = new IncomingTransferCommand(UETR, TestIbans.PARTNER, "Olena Kovalenko",
                TestIbans.A, "Artem Demo", new BigDecimal("250.00"), "UAH", "For the concert tickets", NOW);

        Transfer credited = Transfer.incomingCredited(payment, NOW);
        Transfer refused = Transfer.incomingRejected(payment, IncomingTransferRejection.UNKNOWN_ACCOUNT, NOW);

        assertThat(credited.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(credited.getUetr()).isEqualTo(UETR);
        assertThat(credited.getIdempotencyKey()).isNull();
        assertThat(credited.getFromName()).isEqualTo("Olena Kovalenko");
        assertThat(credited.getRejectionReason()).isNull();
        assertThat(refused.getStatus()).isEqualTo(TransferStatus.REJECTED);
        assertThat(refused.getRejectionReason()).isEqualTo(IncomingTransferRejection.UNKNOWN_ACCOUNT);
        assertThat(refused.isPending()).isFalse();
        assertThatThrownBy(() -> Transfer.incomingRejected(payment, null, NOW)).isInstanceOf(
                NullPointerException.class);
    }

    @Test
    void shouldRejectAnswerForInternalTransfer() {
        assertThatThrownBy(() -> transfer.settle("REPORT-1")).isInstanceOf(IllegalStateException.class);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
    }
}
