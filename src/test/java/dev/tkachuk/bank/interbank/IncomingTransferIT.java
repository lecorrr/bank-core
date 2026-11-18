package dev.tkachuk.bank.interbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.tkachuk.bank.support.IntegrationTest;
import dev.tkachuk.bank.support.KafkaEvents;
import dev.tkachuk.bank.support.TestIbans;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Payments from customers of other banks, through a real PostgreSQL and a real Kafka. The test plays
 * bank-integration: it publishes the IncomingTransferReceived events, and reads the IncomingTransferDecided events
 * bank-core sends back. Every test pays under a UETR of its own, so no test sees another's decisions.
 */
class IncomingTransferIT extends IntegrationTest {

    /** Generous: the first test also waits for bank-core's consumer to join its group. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaContainer kafka;

    private KafkaEvents bankIntegration;

    @BeforeEach
    void openThePayeesAccounts() {
        bankIntegration = new KafkaEvents(kafka, TIMEOUT);
        insertAccount(TestIbans.A, "Artem Demo", "UAH", "100.00");
        insertAccount(TestIbans.D_USD, "Artem Demo USD", "USD", "0.00");
    }

    @Test
    void aPaymentIsCreditedFromTheSettlementAccountAndTheDecisionGoesBack() throws Exception {
        String uetr = UUID.randomUUID().toString();

        receive(uetr, TestIbans.A, "250.00");

        ConsumerRecord<String, String> decision = bankIntegration.eventAbout(IncomingTransferDecided.TOPIC, uetr);
        assertThat(decision.key()).as("keyed by the UETR").isEqualTo(uetr);
        String json = decision.value();
        assertThat(JsonPath.<String>read(json, "$.uetr")).isEqualTo(uetr);
        assertThat(JsonPath.<String>read(json, "$.decision")).isEqualTo("CREDITED");
        assertThat(json).as("no reason for a payment credited").doesNotContain("UNKNOWN_ACCOUNT")
                .doesNotContain("CURRENCY_MISMATCH");
        String transferId = JsonPath.read(json, "$.transferId");

        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("350.00");
        assertThat(balanceOf(TestIbans.SETTLEMENT)).as("the bank's position at the National Bank")
                .isEqualByComparingTo("-250.00");
        mockMvc.perform(get("/api/v1/transfers/{id}", transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.fromIban").value(TestIbans.PARTNER))
                .andExpect(jsonPath("$.fromName").value("Olena Kovalenko"))
                .andExpect(jsonPath("$.toIban").value(TestIbans.A));
        mockMvc.perform(get("/api/v1/accounts/{iban}/statement", TestIbans.A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].type").value("INCOMING"))
                .andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(250.0))
                .andExpect(jsonPath("$.entries[0].balanceAfter").value(350.0))
                .andExpect(jsonPath("$.entries[0].description").value("For the concert tickets"))
                .andExpect(jsonPath("$.entries[0].transferId").value(transferId));
        // Once Kafka has the decision, the outbox is empty again.
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countRows("event_publication")).isZero());
    }

    @Test
    void aPaymentToAnAccountThisBankDoesNotKeepIsRefusedAndMovesNoMoney() throws Exception {
        String uetr = UUID.randomUUID().toString();

        receive(uetr, TestIbans.UNKNOWN, "250.00");

        String json = bankIntegration.eventAbout(IncomingTransferDecided.TOPIC, uetr).value();
        assertThat(JsonPath.<String>read(json, "$.decision")).isEqualTo("REJECTED");
        assertThat(JsonPath.<String>read(json, "$.reason")).isEqualTo("UNKNOWN_ACCOUNT");
        assertThat(balanceOf(TestIbans.SETTLEMENT)).isEqualByComparingTo("0.00");
        assertThat(countRows("ledger_entries")).isZero();
        mockMvc.perform(get("/api/v1/transfers/{id}", JsonPath.<String>read(json, "$.transferId")))
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void aPaymentInAnotherCurrencyThanThePayeesAccountIsRefused() throws Exception {
        String uetr = UUID.randomUUID().toString();

        receive(uetr, TestIbans.D_USD, "250.00");

        String json = bankIntegration.eventAbout(IncomingTransferDecided.TOPIC, uetr).value();
        assertThat(JsonPath.<String>read(json, "$.reason")).isEqualTo("CURRENCY_MISMATCH");
        assertThat(balanceOf(TestIbans.D_USD)).isEqualByComparingTo("0.00");
    }

    @Test
    void aCopyOfAPaymentIsCreditedOnceAndGetsTheSameDecisionAgain() throws Exception {
        String uetr = UUID.randomUUID().toString();

        receive(uetr, TestIbans.A, "250.00");
        receive(uetr, TestIbans.A, "250.00");

        List<ConsumerRecord<String, String>> decisions =
                bankIntegration.eventsAbout(IncomingTransferDecided.TOPIC, uetr, 2);
        assertThat(JsonPath.<String>read(decisions.get(1).value(), "$.transferId"))
                .isEqualTo(JsonPath.<String>read(decisions.get(0).value(), "$.transferId"));
        assertThat(JsonPath.<String>read(decisions.get(1).value(), "$.decision")).isEqualTo("CREDITED");
        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("350.00");
        assertThat(countRows("ledger_entries")).as("one debit and one credit").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE uetr = ?::uuid", Integer.class, uetr))
                .isEqualTo(1);
    }

    @Test
    void anEventThatCannotBeReadIsSkippedAndTheNextPaymentIsStillCredited() throws Exception {
        String uetr = UUID.randomUUID().toString();

        // Keyed like the payment after it, so the two share a partition, where the second must not wait behind it.
        bankIntegration.publish(IncomingTransferReceived.TOPIC, TestIbans.A, "not json");
        receive(uetr, TestIbans.A, "250.00");

        String json = bankIntegration.eventAbout(IncomingTransferDecided.TOPIC, uetr).value();
        assertThat(JsonPath.<String>read(json, "$.decision")).isEqualTo("CREDITED");
    }

    /** Brings in a payment as bank-integration does: JSON text, keyed by the payee's IBAN. */
    private void receive(String uetr, String toIban, String amount) throws Exception {
        bankIntegration.publish(IncomingTransferReceived.TOPIC, toIban, """
                {"uetr": "%s",
                 "fromIban": "%s", "fromName": "Olena Kovalenko",
                 "toIban": "%s", "toName": "Artem Demo",
                 "amount": %s, "currency": "UAH", "description": "For the concert tickets",
                 "receivedAt": "2026-11-18T09:15:00Z"}
                """.formatted(uetr, TestIbans.PARTNER, toIban, amount));
    }
}
