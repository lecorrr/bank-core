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
import dev.tkachuk.bank.support.KafkaEvents;
import dev.tkachuk.bank.support.TestIbans;
import dev.tkachuk.bank.support.Traces;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.kafka.KafkaContainer;

class InterbankTransferIT extends IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaContainer kafka;

    private KafkaEvents bankIntegration;

    @BeforeEach
    void openThePayersAccount() {
        bankIntegration = new KafkaEvents(kafka, TIMEOUT);
        insertAccount(TestIbans.A, "Artem Tkachuk", "UAH", "1000.00");
    }

    @Test
    void shouldHoldTransferAndPublishEvent() throws Exception {
        String transferId = sendToPartner("key-1");

        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("850.00");
        assertThat(balanceOf(TestIbans.TRANSIT)).isEqualByComparingTo("150.00");

        ConsumerRecord<String, String> event = bankIntegration.eventAbout(TransferRequested.TOPIC, transferId);
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

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(countRows("event_publication")).isZero());
    }

    @Test
    void shouldContinueTheCallersTraceInTheEvent() throws Exception {
        String callerTrace = Traces.traceParent("0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331");

        String transferId = sendToPartner("key-1", callerTrace);

        ConsumerRecord<String, String> event = bankIntegration.eventAbout(TransferRequested.TOPIC, transferId);
        String traceParent = KafkaEvents.header(event, Traces.TRACE_PARENT);
        assertThat(Traces.traceIdOf(traceParent)).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(Traces.spanIdOf(traceParent)).as("a span of bank-core's own").isNotEqualTo("b7ad6b7169203331");
    }

    @Test
    void shouldStartATraceForACallWithoutOne() throws Exception {
        String transferId = sendToPartner("key-1");

        ConsumerRecord<String, String> event = bankIntegration.eventAbout(TransferRequested.TOPIC, transferId);
        assertThat(Traces.traceIdOf(KafkaEvents.header(event, Traces.TRACE_PARENT))).isNotBlank();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldLogTheAnswerInBankIntegrationsTrace(CapturedOutput output) throws Exception {
        String transferId = sendToPartner("key-1");

        answer(transferId, "SETTLED", "REPORT-1",
                Traces.traceParent("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7"));

        assertThat(Traces.awaitLogLine(output, "Transfer " + transferId + " is SETTLED", TIMEOUT))
                .contains("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void shouldCompleteTransferWhenAccepted() throws Exception {
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
    void shouldReturnMoneyWhenRejected() throws Exception {
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
    void shouldIgnoreRepeatedAndConflictingAnswers() throws Exception {
        String first = sendToPartner("key-1");
        String second = sendToPartner("key-2");

        answer(first, "SETTLED", "REPORT-1");
        answer(first, "SETTLED", "REPORT-1");
        answer(first, "REJECTED", "REPORT-2");
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
    void shouldSkipUnreadableEventAndContinue() throws Exception {
        String transferId = sendToPartner("key-1");

        bankIntegration.publish(TransferStatusChanged.TOPIC, transferId, "not json");
        answer(transferId, "SETTLED", "REPORT-1");

        awaitStatus(transferId, "COMPLETED");
    }

    @Test
    void shouldHideBankOwnAccounts() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].iban").value(TestIbans.A));
        mockMvc.perform(get("/api/v1/accounts/{iban}/statement", TestIbans.TRANSIT))
                .andExpect(status().isNotFound());
    }

    private String sendToPartner(String idempotencyKey) throws Exception {
        return sendToPartner(idempotencyKey, null);
    }

    private String sendToPartner(String idempotencyKey, String traceParent) throws Exception {
        String body = """
                {"fromIban": "%s", "toIban": "%s", "toName": "Olena Kovalenko",
                 "amount": 150.00, "currency": "UAH", "description": "Rent"}
                """.formatted(TestIbans.A, TestIbans.PARTNER);
        MockHttpServletRequestBuilder request = post("/api/v1/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (traceParent != null) {
            request.header(Traces.TRACE_PARENT, traceParent);
        }
        String response = mockMvc.perform(request)
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

    private void answer(String transferId, String status, String reportId) throws Exception {
        answer(transferId, status, reportId, null);
    }

    private void answer(String transferId, String status, String reportId, String traceParent) throws Exception {
        bankIntegration.publish(TransferStatusChanged.TOPIC, transferId, """
                {"transferId": "%s", "status": "%s", "reportId": "%s", "reportedAt": "2026-11-17T20:17:09Z"}
                """.formatted(transferId, status, reportId),
                traceParent == null ? Map.of() : Map.of(Traces.TRACE_PARENT, traceParent));
    }
}
