package dev.tkachuk.bank.support;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.kafka.KafkaContainer;

/** Kafka as bank-integration uses it in the integration tests: it publishes JSON text, and reads bank-core's events. */
public final class KafkaEvents {

    private final KafkaContainer kafka;
    private final Duration timeout;

    public KafkaEvents(KafkaContainer kafka, Duration timeout) {
        this.kafka = kafka;
        this.timeout = timeout;
    }

    public void publish(String topic, String key, String value) throws Exception {
        Map<String, Object> config = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, key, value)).get(timeout.toSeconds(), TimeUnit.SECONDS);
        }
    }

    /** The first event whose text contains this id; fails when none comes within the timeout. */
    public ConsumerRecord<String, String> eventAbout(String topic, String id) {
        return eventsAbout(topic, id, 1).getFirst();
    }

    /**
     * The first events whose text contains this id, as many as asked for, read from the start of the topic in a
     * consumer group of its own; fails when fewer come within the timeout.
     */
    public List<ConsumerRecord<String, String>> eventsAbout(String topic, String id, int count) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(timeout);
            while (found.size() < count && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value() != null && record.value().contains(id) && found.size() < count) {
                        found.add(record);
                    }
                }
            }
        }
        if (found.size() < count) {
            throw new AssertionError(count + " event(s) about " + id + " on " + topic + " expected within " + timeout
                    + ", " + found.size() + " came");
        }
        return found;
    }
}
