package dev.tkachuk.bank.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A real Kafka in Docker for integration tests; Spring points bank-core at it automatically. The image is the one
 * bank-integration's compose file runs.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainer {

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka-native:4.4.0");
    }
}
