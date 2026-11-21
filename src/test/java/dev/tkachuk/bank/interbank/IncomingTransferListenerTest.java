package dev.tkachuk.bank.interbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.tkachuk.bank.support.TestIbans;
import dev.tkachuk.bank.transfer.IncomingTransferCommand;
import dev.tkachuk.bank.transfer.IncomingTransferDecision;
import dev.tkachuk.bank.transfer.IncomingTransfers;
import dev.tkachuk.bank.transfer.IncomingTransfers.Outcome;
import dev.tkachuk.bank.transfer.IncomingTransfers.Received;
import java.math.BigDecimal;
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
class IncomingTransferListenerTest {

    private static final UUID UETR = UUID.fromString("3b9f7a52-1c4d-4e8f-a0b6-7d2e5c9f1a34");
    private static final Instant RECEIVED_AT = Instant.parse("2026-11-18T09:15:00Z");

    private static final String PAYMENT = """
            {"uetr": "3b9f7a52-1c4d-4e8f-a0b6-7d2e5c9f1a34",
             "fromIban": "UA643888880000000000000000777", "fromName": "Olena Kovalenko",
             "toIban": "UA843999990000000000000000001", "toName": "Artem Demo",
             "amount": 250.00, "currency": "UAH", "description": "For the concert tickets",
             "receivedAt": "2026-11-18T09:15:00Z"}
            """;

    private static final IncomingTransferCommand COMMAND = new IncomingTransferCommand(UETR, TestIbans.PARTNER,
            "Olena Kovalenko", TestIbans.A, "Artem Demo", new BigDecimal("250.00"), "UAH", "For the concert tickets",
            RECEIVED_AT);

    @Mock
    private IncomingTransfers incomingTransfers;

    private IncomingTransferListener listener;

    @BeforeEach
    void setUp() {
        listener = new IncomingTransferListener(incomingTransfers);
    }

    @Test
    void shouldReadEventAsBankIntegrationWritesIt() {
        assertThat(listener.read(record(PAYMENT))).contains(new IncomingTransferReceived(UETR, TestIbans.PARTNER,
                "Olena Kovalenko", TestIbans.A, "Artem Demo", new BigDecimal("250.00"), "UAH",
                "For the concert tickets", RECEIVED_AT));
    }

    @Test
    void shouldIgnoreUnknownFields() {
        String withAnEndToEndId = PAYMENT.replace("\"amount\"", "\"endToEndId\": \"NOTPROVIDED\", \"amount\"");

        assertThat(listener.read(record(withAnEndToEndId))).isPresent();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not json", "[]", "{}", "{\"fromIban\": \"UA643888880000000000000000777\"}"})
    void shouldSkipEventThatIsNotAPayment(String json) {
        assertThat(listener.read(record(json))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "3b9f7a52-1c4d-4e8f-a0b6-7d2e5c9f1a34|not-a-uuid",
            "\"amount\": 250.00|\"amount\": -250.00",
            "\"amount\": 250.00|\"amount\": 250.005",
            "\"currency\": \"UAH\"|\"currency\": \"uah\"",
            "\"fromName\": \"Olena Kovalenko\"|\"fromName\": \" \"",
            "\"toIban\": \"UA843999990000000000000000001\"|\"toIban\": null",
            "\"receivedAt\": \"2026-11-18T09:15:00Z\"|\"receivedAt\": null",
            "For the concert tickets|" + "For the concert tickets, and for the taxi to the concert hall, and for the "
                    + "programme, and for the coat check, and for the drinks in the interval, and so on"
    })
    void shouldSkipPaymentThatBreaksARule(String change) {
        String[] parts = change.split("\\|");
        assertThat(PAYMENT).as("the case must change the payment").contains(parts[0]);

        assertThat(listener.read(record(PAYMENT.replace(parts[0], parts[1])))).isEmpty();
    }

    @Test
    void shouldNotThrowForSkippedEvent() {
        listener.on(record("not json"));

        verifyNoInteractions(incomingTransfers);
    }

    @Test
    void shouldPassPaymentToIncomingTransfers() {
        when(incomingTransfers.receive(COMMAND)).thenReturn(new Received(Outcome.CREDITED,
                new IncomingTransferDecision(UETR, UUID.randomUUID(), null, RECEIVED_AT, null)));

        listener.on(record(PAYMENT));

        verify(incomingTransfers).receive(COMMAND);
    }

    @Test
    void shouldThrowWhenPaymentCannotBeDecidedNow() {
        when(incomingTransfers.receive(any(IncomingTransferCommand.class)))
                .thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> listener.on(record(PAYMENT))).hasMessage("database down");
    }

    private static ConsumerRecord<String, String> record(String json) {
        return new ConsumerRecord<>(IncomingTransferReceived.TOPIC, 0, 7L, TestIbans.A, json);
    }
}
