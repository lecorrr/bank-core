package dev.tkachuk.bank.interbank;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.bank.common.CurrentTrace;
import dev.tkachuk.bank.interbank.IncomingTransferDecided.Decision;
import dev.tkachuk.bank.support.TestIbans;
import dev.tkachuk.bank.transfer.IncomingTransferDecision;
import dev.tkachuk.bank.transfer.IncomingTransferRejection;
import dev.tkachuk.bank.transfer.InterbankTransferPending;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.messaging.Message;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;

class InterbankConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-11-18T09:15:02Z");
    private static final UUID UETR = UUID.fromString("3b9f7a52-1c4d-4e8f-a0b6-7d2e5c9f1a34");
    private static final UUID TRANSFER_ID = UUID.fromString("9d1c4b7e-2f3a-4e5b-8c6d-0a1b2c3d4e5f");
    private static final String TRACE_PARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final EventExternalizationConfiguration externalization =
            new InterbankConfiguration().eventExternalizationConfiguration();

    @Test
    void shouldPublishTransferRequestedKeyedByPayer() {
        InterbankTransferPending pending = pending(TRACE_PARENT);

        assertThat(externalization.supports(pending)).isTrue();
        assertThat(messageOf(pending).getPayload()).isEqualTo(TransferRequested.of(pending));
        RoutingTarget target = externalization.determineTarget(pending);
        assertThat(target.getTarget()).isEqualTo(TransferRequested.TOPIC);
        assertThat(target.getKey()).isEqualTo(TestIbans.A);
    }

    @Test
    void shouldSendTheStoredTraceWithTheTransfer() {
        assertThat(messageOf(pending(TRACE_PARENT)).getHeaders().get(CurrentTrace.TRACE_PARENT))
                .isEqualTo(TRACE_PARENT);
    }

    @Test
    void shouldSendNoTraceHeaderWhenNoneWasStored() {
        assertThat(messageOf(pending(null)).getHeaders()).doesNotContainKey(CurrentTrace.TRACE_PARENT);
    }

    @Test
    void shouldPublishDecisionKeyedByUetrWithItsTrace() {
        IncomingTransferDecision decision = new IncomingTransferDecision(UETR, TRANSFER_ID,
                IncomingTransferRejection.CURRENCY_MISMATCH, NOW, TRACE_PARENT);

        assertThat(externalization.supports(decision)).isTrue();
        Message<?> message = messageOf(decision);
        assertThat(message.getPayload()).isEqualTo(new IncomingTransferDecided(UETR, TRANSFER_ID,
                Decision.REJECTED, "CURRENCY_MISMATCH", NOW));
        assertThat(message.getHeaders().get(CurrentTrace.TRACE_PARENT)).isEqualTo(TRACE_PARENT);
        RoutingTarget target = externalization.determineTarget(decision);
        assertThat(target.getTarget()).isEqualTo(IncomingTransferDecided.TOPIC);
        assertThat(target.getKey()).isEqualTo(UETR.toString());
    }

    @Test
    void shouldPublishCreditedDecisionWithoutReason() {
        IncomingTransferDecision credited = new IncomingTransferDecision(UETR, TRANSFER_ID, null, NOW, null);

        assertThat(messageOf(credited).getPayload()).isEqualTo(new IncomingTransferDecided(UETR, TRANSFER_ID,
                Decision.CREDITED, null, NOW));
    }

    @Test
    void shouldPublishNothingElse() {
        assertThat(externalization.supports("an event of some other kind")).isFalse();
    }

    @Test
    void shouldStartListenersByDefault() {
        assertThat(listenerFactory(true).createContainer(TransferStatusChanged.TOPIC).isAutoStartup()).isTrue();
    }

    @Test
    void shouldKeepListenersOffWhenAutoStartupIsOff() {
        assertThat(listenerFactory(false).createContainer(TransferStatusChanged.TOPIC).isAutoStartup()).isFalse();
    }

    private static ConcurrentKafkaListenerContainerFactory<String, String> listenerFactory(boolean autoStartup) {
        return new InterbankConfiguration().interbankListenerFactory(new DefaultKafkaConsumerFactory<>(Map.of()),
                autoStartup);
    }

    private Message<?> messageOf(Object event) {
        Object mapped = externalization.map(event);
        assertThat(mapped).isInstanceOf(Message.class);
        return (Message<?>) mapped;
    }

    private static InterbankTransferPending pending(String traceParent) {
        return new InterbankTransferPending(TRANSFER_ID, TestIbans.A, "Artem Demo", TestIbans.PARTNER,
                "Olena Kovalenko", new BigDecimal("150.00"), "UAH", "Rent", NOW, traceParent);
    }
}
