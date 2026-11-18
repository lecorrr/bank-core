package dev.tkachuk.bank.interbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.tkachuk.bank.support.IntegrationTest;
import dev.tkachuk.bank.support.TestIbans;
import java.time.Duration;
import java.time.Instant;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Transfers to another bank, through a real PostgreSQL and a real Kafka. The test plays bank-integration: it reads
 * the TransferRequested events bank-core sends, and answers with TransferStatusChanged events as bank-integration
 * does once the other bank's pacs.002 has come. Kafka here gives each topic one partition, so bank-core reads the
 * answers in the order the test sends them.
 */
class InterbankTransferIT extends IntegrationTest {

    /** Generous: the first test also waits for bank-core's consumer to join its group. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaContainer kafka;

    @BeforeEach
    void openThePayersAccount() {
        insertAccount(TestIbans.A, "Artem Tkachuk", "UAH", "1000.00");
    }

    @Test
    void theTransferWaitsForTheOtherBankAndGoesOutAsAnEvent() throws Exception {
        String transferId = sendToPartner("key-1");

        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("850.00");
        assertThat(balanceOf(TestIbans.TRANSIT)).isEqualByComparingTo("150.00");

        ConsumerRecord<String, String> event = eventAbout(TransferRequested.TOPIC, transferId);
        assertThat(event.key()).as("keyed by the payer's IBAN").isEqualTo(TestIbans.A);
        String json = event.value();
        assertThat(JsonPath.<String>read(json, "$.transferId")).isEqualTo(transferId);
        assertThat(JsonPath.<String>read(json, "$.fromIban")).isEqualTo(TestIbans.A);
        assertThat(JsonPath.<String>read(json, "$.fromName")).isEqualTo("Artem Tkachuk");
        assertThat(JsonPath.<String>read(json, "$.toIban")).isEqualTo(TestIbans.PARTNER);
        assertThat(JsonPath.<String>read(json, "$.toName")).isEqualTo("Olena Kovalenko");
        assertThat(json).as("the amount as a number with two decimals").contains("\"amount\":150.00");
        assertThat(JsonPath.<String>read(json, "$.currency")).isEqualTo("UAH");
        assertThat(JsonPath.<String>read(json, "$.description")).isEqualTo("Rent");
        Object requestedAt = JsonPath.read(json, "$.requestedAt");
        assertThat(requestedAt).as("an ISO-8601 time, as bank-integration reads it").isInstanceOf(String.class);
        assertThat(Instant.parse((String) requestedAt)).isNotNull();

        // Once Kafka has the event, the outbox is empty again.
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countRows("event_publication")).isZero());
    }

    @Test
    void acceptedByTheOtherBankTheTransferIsCompleted() throws Exception {
        String transferId = sendToPartner("key-1");

        answer(transferId, "SETTLED", "REPORT-1");

        awaitStatus(transferId, "COMPLETED");
        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("850.00");
        assertThat(balanceOf(TestIbans.TRANSIT)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(TestIbans.SETTLEMENT)).isEqualByComparingTo("150.00");
        assertThat(jdbc.queryForObject("SELECT status_report_id FROM transfers WHERE id = ?::uuid", String.class,
                transferId)).isEqualTo("REPORT-1");
    }

    @Test
    void refusedByTheOtherBankTheMoneyComesBack() throws Exception {
        String transferId = sendToPartner("key-1");

        answer(transferId, "REJECTED", "REPORT-1");

        awaitStatus(transferId, "REJECTED");
        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(TestIbans.TRANSIT)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(TestIbans.SETTLEMENT)).isEqualByComparingTo("0.00");
        mockMvc.perform(get("/api/v1/accounts/{iban}/statement", TestIbans.A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1000.0))
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[1].type").value("REVERSAL"))
                .andExpect(jsonPath("$.entries[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[1].balanceAfter").value(1000.0))
                .andExpect(jsonPath("$.entries[1].description").value("Returned: Rent"))
                .andExpect(jsonPath("$.entries[1].transferId").value(transferId));
    }

    @Test
    void repeatedAndContradictingAnswersChangeNothing() throws Exception {
        String first = sendToPartner("key-1");
        String second = sendToPartner("key-2");

        answer(first, "SETTLED", "REPORT-1");
        answer(first, "SETTLED", "REPORT-1");
        answer(first, "REJECTED", "REPORT-2");
        // Read after the three above, so once it is applied they have been handled too.
        answer(second, "SETTLED", "REPORT-3");

        awaitStatus(second, "COMPLETED");
        mockMvc.perform(get("/api/v1/transfers/{id}", first)).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("700.00");
        assertThat(balanceOf(TestIbans.TRANSIT)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(TestIbans.SETTLEMENT)).isEqualByComparingTo("300.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE transfer_id = ?::uuid",
                Integer.class, first)).as("two entries for the transfer, two for its settlement").isEqualTo(4);
    }

    @Test
    void anEventThatCannotBeReadIsSkippedAndTheNextOneStillApplies() throws Exception {
        String transferId = sendToPartner("key-1");

        publish(TransferStatusChanged.TOPIC, transferId, "not json");
        answer(transferId, "SETTLED", "REPORT-1");

        awaitStatus(transferId, "COMPLETED");
    }

    @Test
    void theBanksOwnAccountsStayHidden() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].iban").value(TestIbans.A));
        mockMvc.perform(get("/api/v1/accounts/{iban}/statement", TestIbans.TRANSIT))
                .andExpect(status().isNotFound());
    }

    /** Makes the transfer through the API, as a customer would, and returns its id. */
    private String sendToPartner(String idempotencyKey) throws Exception {
        String body = """
                {"fromIban": "%s", "toIban": "%s", "toName": "Olena Kovalenko",
                 "amount": 150.00, "currency": "UAH", "description": "Rent"}
                """.formatted(TestIbans.A, TestIbans.PARTNER);
        String response = mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private void awaitStatus(String transferId, String expected) {
        await().atMost(TIMEOUT).untilAsserted(() -> mockMvc.perform(get("/api/v1/transfers/{id}", transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expected)));
    }

    /** Answers as bank-integration does: JSON text, keyed by the transfer id. */
    private void answer(String transferId, String status, String reportId) throws Exception {
        publish(TransferStatusChanged.TOPIC, transferId, """
                {"transferId": "%s", "status": "%s", "reportId": "%s", "reportedAt": "2026-11-17T20:17:09Z"}
                """.formatted(transferId, status, reportId));
    }

    private void publish(String topic, String key, String value) throws Exception {
        Map<String, Object> config = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, key, value)).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
    }

    /** The event about this transfer, read from the start of the topic in a consumer group of its own. */
    private ConsumerRecord<String, String> eventAbout(String topic, String transferId) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value() != null && record.value().contains(transferId)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No event about transfer " + transferId + " on " + topic + " within " + TIMEOUT);
    }
}
