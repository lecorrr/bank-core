package dev.tkachuk.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.bank.support.IntegrationTest;
import dev.tkachuk.bank.support.TestIbans;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ApplicationIT extends IntegrationTest {

    @Test
    void shouldReportHealthUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void shouldMigrateSchemaWithFlyway() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldServeApiContract() throws Exception {
        mockMvc.perform(get("/openapi/bank-core.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("operationId: createTransfer")));
    }

    @Test
    void shouldAddRequestIdToEveryResponse() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void shouldKeepSafeIncomingRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").header("X-Request-Id", "abc-123"))
                .andExpect(header().string("X-Request-Id", "abc-123"));
    }

    @Test
    void shouldReplaceUnsafeIncomingRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").header("X-Request-Id", "abc 123\nfake log line"))
                .andExpect(header().string("X-Request-Id", not("abc 123\nfake log line")));
    }

    @Test
    void shouldEnforcePathVariableConstraints() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{iban}", "UA12"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0]").value(startsWith("iban:")));
    }

    @Test
    void shouldEnforceHeaderConstraints() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "k".repeat(101))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromIban": "%s", "toIban": "%s", "amount": 1.00, "currency": "UAH",
                                 "description": "Too long a key"}
                                """.formatted(TestIbans.A, TestIbans.B)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0]").value(startsWith("idempotencyKey:")));
    }
}
