package dev.tkachuk.bank.interbank;

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
 * answers are read. Scheduling is on for the outbox: FailedEventResubmission, and Spring Modulith's check for
 * publications a crash left unfinished.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class InterbankConfiguration {

    static final String STATUS_LISTENER_FACTORY = "transferStatusListenerFactory";

    /** How long an answer that cannot be applied right now waits before the next try. */
    private static final long RETRY_INTERVAL_MS = 5_000;

    /**
     * Only InterbankTransferPending leaves bank-core. On the wire it becomes a TransferRequested, keyed by the payer's
     * IBAN as bank-integration expects. Spring Modulith writes it to Kafka as JSON with Spring Boot's JsonMapper.
     */
    @Bean
    EventExternalizationConfiguration eventExternalizationConfiguration() {
        return EventExternalizationConfiguration.externalizing()
                .selectByType(InterbankTransferPending.class)
                .mapping(InterbankTransferPending.class, TransferRequested::of)
                .route(InterbankTransferPending.class,
                        pending -> RoutingTarget.forTarget(TransferRequested.TOPIC).andKey(pending.fromIban()))
                .build();
    }

    /**
     * Status events arrive as JSON text, which TransferStatusListener reads itself. Built from Spring Boot's
     * consumer settings (the broker's address among them) with text deserializers instead of the byte arrays
     * Spring Modulith sets for JSON.
     *
     * The error handler retries an answer that failed every 5 seconds and never gives up: skipping it would leave
     * the transfer PENDING for good. Events that cannot be read never reach it; the listener skips those.
     */
    @Bean(STATUS_LISTENER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, String> transferStatusListenerFactory(
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
