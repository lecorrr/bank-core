package dev.tkachuk.bank.interbank;

import dev.tkachuk.bank.common.CurrentTrace;
import dev.tkachuk.bank.transfer.IncomingTransferDecision;
import dev.tkachuk.bank.transfer.InterbankTransferPending;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
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
                .mapping(InterbankTransferPending.class,
                        pending -> withTrace(TransferRequested.of(pending), pending.traceParent()))
                .mapping(IncomingTransferDecision.class,
                        decision -> withTrace(IncomingTransferDecided.of(decision), decision.traceParent()))
                .route(InterbankTransferPending.class,
                        pending -> RoutingTarget.forTarget(TransferRequested.TOPIC).andKey(pending.fromIban()))
                .route(IncomingTransferDecision.class, decision ->
                        RoutingTarget.forTarget(IncomingTransferDecided.TOPIC).andKey(decision.uetr().toString()))
                .build();
    }

    // spring.kafka.listener.auto-startup=false keeps the listeners off where no Kafka runs yet, such as the k3d
    // cluster; this factory is our own, so Spring Boot's listener settings do not reach it by themselves.
    @Bean(LISTENER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, String> interbankListenerFactory(
            ConsumerFactory<?, ?> consumerFactory,
            @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setAutoStartup(autoStartup);
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumerFactory.getConfigurationProperties(),
                new StringDeserializer(), new StringDeserializer()));
        // Retry every 5 s, forever: skipping an answer would leave its transfer PENDING for good.
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                new FixedBackOff(RETRY_INTERVAL_MS, FixedBackOff.UNLIMITED_ATTEMPTS)));
        // A span per record that continues bank-integration's traceparent, so the listener logs show its trace.
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    // The trace stored with the event goes into the record's traceparent header, so an event resent after a
    // Kafka outage still continues the request's trace. KafkaTemplate's own observation stays off: it would
    // write the trace of the thread that sends instead.
    static Message<Object> withTrace(Object event, String traceParent) {
        return MessageBuilder.withPayload(event).setHeader(CurrentTrace.TRACE_PARENT, traceParent).build();
    }
}
