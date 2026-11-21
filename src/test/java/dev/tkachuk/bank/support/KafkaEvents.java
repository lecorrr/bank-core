package dev.tkachuk.bank.support;

import java.nio.charset.StandardCharsets;
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
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.kafka.KafkaContainer;

/** Publishes and reads Kafka events the way bank-integration does. */
public final class KafkaEvents {

    private final KafkaContainer kafka;
    private final Duration timeout;

    public KafkaEvents(KafkaContainer kafka, Duration timeout) {
        this.kafka = kafka;
        this.timeout = timeout;
    }

    public void publish(String topic, String key, String value) throws Exception {
        publish(topic, key, value, Map.of());
    }

    public void publish(String topic, String key, String value, Map<String, String> headers) throws Exception {
        Map<String, Object> config = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        headers.forEach((name, text) -> record.headers().add(name, text.getBytes(StandardCharsets.UTF_8)));
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            producer.send(record).get(timeout.toSeconds(), TimeUnit.SECONDS);
        }
    }

    /** The record's last header with that name as text, or null. */
    public static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    public ConsumerRecord<String, String> eventAbout(String topic, String id) {
        return eventsAbout(topic, id, 1).getFirst();
    }

    public List<ConsumerRecord<String, String>> eventsAbout(String topic, String id, int count) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                // A new group every time, so each call reads the topic from the start.
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
