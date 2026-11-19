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

/** The Kafka side of the contract with bank-integration: outgoing events via the outbox, and the listeners. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class InterbankConfiguration {

    static final String LISTENER_FACTORY = "interbankListenerFactory";

    private static final long RETRY_INTERVAL_MS = 5_000;

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

    @Bean(LISTENER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, String> interbankListenerFactory(
            ConsumerFactory<?, ?> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumerFactory.getConfigurationProperties(),
                new StringDeserializer(), new StringDeserializer()));
        // Retry every 5 s, forever: skipping an answer would leave its transfer PENDING for good.
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                new FixedBackOff(RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS)));
        return factory;
    }
}
