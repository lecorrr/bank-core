package dev.tkachuk.bank.interbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.tkachuk.bank.interbank.TransferStatusChanged.Status;
import dev.tkachuk.bank.transfer.TransferSettlement;
import dev.tkachuk.bank.transfer.TransferSettlement.Outcome;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransferStatusListenerTest {

    private static final UUID TRANSFER_ID = UUID.fromString("0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e");
    private static final String REPORT_ID = "b076ecf1eb4146f4a1fec9042f425982";

    private static final String SETTLED = """
            {"transferId": "0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e", "status": "SETTLED",
             "reportId": "b076ecf1eb4146f4a1fec9042f425982", "reportedAt": "2026-11-17T20:17:09Z"}
            """;

    @Mock
    private TransferSettlement settlement;

    private TransferStatusListener listener;

    @BeforeEach
    void setUp() {
        listener = new TransferStatusListener(settlement);
    }

    @Test
    void shouldReadEventAsBankIntegrationWritesIt() {
        assertThat(listener.read(record(SETTLED))).contains(new TransferStatusChanged(TRANSFER_ID, Status.SETTLED,
                REPORT_ID, Instant.parse("2026-11-17T20:17:09Z")));
    }

    @Test
    void shouldIgnoreUnknownFields() {
        String withAReason = SETTLED.replace("\"status\"", "\"reason\": \"AC04\", \"status\"");

        assertThat(listener.read(record(withAReason))).isPresent();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "not json",
            "[]",
            "{}",
            "{\"transferId\": \"not-a-uuid\", \"status\": \"SETTLED\", \"reportId\": \"R1\"}",
            "{\"transferId\": \"0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e\", \"status\": \"CANCELLED\", "
                    + "\"reportId\": \"R1\"}",
            "{\"transferId\": \"0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e\", \"reportId\": \"R1\"}",
            "{\"transferId\": \"0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e\", \"status\": \"SETTLED\"}",
            "{\"transferId\": \"0f5bd7c2-3a4e-4c1b-9d0e-6f2a8b1c3d4e\", \"status\": \"SETTLED\", "
                    + "\"reportId\": \"123456789012345678901234567890123456\"}"
    })
    void shouldSkipEventThatCannotBeApplied(String json) {
        assertThat(listener.read(record(json))).isEmpty();
    }

    @Test
    void shouldNotThrowForSkippedEvent() {
        listener.on(record("not json"));

        verifyNoInteractions(settlement);
    }

    @Test
    void shouldSettleAcceptedTransfer() {
        when(settlement.settle(TRANSFER_ID, REPORT_ID)).thenReturn(Outcome.APPLIED);

        listener.on(record(SETTLED));

        verify(settlement).settle(TRANSFER_ID, REPORT_ID);
    }

    @Test
    void shouldRejectRefusedTransfer() {
        when(settlement.reject(TRANSFER_ID, REPORT_ID)).thenReturn(Outcome.APPLIED);

        listener.on(record(SETTLED.replace("SETTLED", "REJECTED")));

        verify(settlement).reject(TRANSFER_ID, REPORT_ID);
    }

    @Test
    void shouldIgnorePendingStatus() {
        listener.on(record(SETTLED.replace("SETTLED", "PENDING")));

        verifyNoInteractions(settlement);
    }

    @Test
    void shouldThrowWhenAnswerCannotBeAppliedNow() {
        when(settlement.settle(TRANSFER_ID, REPORT_ID)).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> listener.on(record(SETTLED))).hasMessage("database down");
    }

    private static ConsumerRecord<String, String> record(String json) {
        return new ConsumerRecord<>(TransferStatusChanged.TOPIC, 0, 7L, TRANSFER_ID.toString(), json);
    }
}
