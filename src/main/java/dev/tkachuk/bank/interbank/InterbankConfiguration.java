package dev.tkachuk.bank.interbank;

import dev.tkachuk.bank.transfer.IncomingTransferDecision;
import dev.tkachuk.bank.transfer.InterbankTransferPending;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.RoutingTarget;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

/**
 * bank-core's side of the Kafka contract with bank-integration: which events go out and how they look, and how the
 * events coming in are read. Scheduling is on for the outbox: FailedEventResubmission, and Spring Modulith's check
 * for publications a crash left unfinished.
 *
 * <pre>
 * out  interbank.transfers                     TransferRequested         a transfer to another bank
 * in   interbank.transfer-statuses             TransferStatusChanged     that bank's answer
 * in   interbank.incoming-transfers            IncomingTransferReceived  a payment from another bank
 * out  interbank.incoming-transfer-decisions   IncomingTransferDecided   this bank's decision on it
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class InterbankConfiguration {

    static final String LISTENER_FACTORY = "interbankListenerFactory";

    /** How long an event that cannot be applied right now waits before the next try. */
    private static final long RETRY_INTERVAL_MS = 5_000;

    /**
     * Two events leave bank-core, each turned into its wire form and keyed as bank-integration expects:
     * InterbankTransferPending as a TransferRequested, keyed by the payer's IBAN, and IncomingTransferDecision as an
     * IncomingTransferDecided, keyed by the UETR. Spring Modulith writes them to Kafka as JSON with Spring Boot's
     * JsonMapper. A type-specific mapping or route leaves the other type's events to the next one.
     */
    @Bean
    EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .select(event -> event instanceof InterbankTransferPending || event instanceof IncomingTransferDecision)
                .mapping(InterbankTransferPending.class, TransferRequested::of)
                .mapping(IncomingTransferDecision.class, IncomingTransferDecided::of)
                .route(InterbankTransferPending.class,
                        pending -> RoutingTarget.forTarget(TransferRequested.TOPIC).andKey(pending.fromIban()))
                .route(IncomingTransferDecision.class, decision ->
                        RoutingTarget.forTarget(IncomingTransferDecided.TOPIC).andKey(decision.uetr().toString()))
                .build();
    }

    /**
     * The events coming in arrive as JSON text, which each listener reads itself (InterbankJson). Built from Spring
     * Boot's consumer settings (the broker's address among them) with text deserializers instead of the byte
     * arrays Spring Modulith sets for JSON.
     *
     * The error handler retries an event that failed every 5 seconds and never gives up: skipping an answer would
     * leave its transfer PENDING for good, and skipping a payment would leave it without a decision. Events that
     * cannot be read never reach it; the listeners skip those.
     */
    @Bean(LISTENER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, String> interbankListenerFactory(
            ConsumerFactory<?, ?> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumerFactory.getConfigurationProperties(),
                new StringDeserializer(), new StringDeserializer()));
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                new FixedBackOff(RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
}
