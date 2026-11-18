package dev.tkachuk.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.tkachuk.bank.support.IntegrationTest;
import dev.tkachuk.bank.support.TestIbans;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.RequestBuilder;

/** Transfers end to end: HTTP, the transaction and the database constraints together. */
class TransferFlowIT extends IntegrationTest {

    @BeforeEach
    void openAccounts() {
        insertAccount(TestIbans.A, "Artem Demo", "UAH", "1000.00");
        insertAccount(TestIbans.B, "Olena Kovalenko", "UAH", "0.00");
        insertAccount(TestIbans.D_USD, "Artem Demo USD", "USD", "1000.00");
    }

    @Test
    void movesMoneyAndRecordsBothSides() throws Exception {
        String body = mockMvc.perform(transfer("key-1", TestIbans.A, TestIbans.B, "150.00"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        String transferId = JsonPath.read(body, "$.id");

        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("850.00");
        assertThat(balanceOf(TestIbans.B)).isEqualByComparingTo("150.00");
        assertThat(countRows("transfers")).isEqualTo(1);
        assertThat(countRows("ledger_entries")).isEqualTo(2);

        mockMvc.perform(get("/api/v1/accounts/{iban}/statement", TestIbans.A))
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$.entries[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.entries[0].balanceAfter").value(850.0))
                .andExpect(jsonPath("$.entries[0].transferId").value(transferId));
        mockMvc.perform(get("/api/v1/accounts/{iban}/statement", TestIbans.B))
                .andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[0].balanceAfter").value(150.0))
                .andExpect(jsonPath("$.entries[0].transferId").value(transferId));
        mockMvc.perform(get("/api/v1/transfers/{id}", transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromIban").value(TestIbans.A))
                .andExpect(jsonPath("$.amount").value(150.0));
    }

    @Test
    void sendingTheSameRequestAgainMovesNoMoreMoney() throws Exception {
        String first = mockMvc.perform(transfer("key-1", TestIbans.A, TestIbans.B, "150.00"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(transfer("key-1", TestIbans.A, TestIbans.B, "150.00"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(JsonPath.<String>read(first, "$.id")));

        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("850.00");
        assertThat(countRows("transfers")).isEqualTo(1);
        assertThat(countRows("ledger_entries")).isEqualTo(2);
    }

    @Test
    void reusingAKeyForAnotherRequestIsRefused() throws Exception {
        mockMvc.perform(transfer("key-1", TestIbans.A, TestIbans.B, "150.00")).andExpect(status().isCreated());

        mockMvc.perform(transfer("key-1", TestIbans.A, TestIbans.B, "999.00"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("850.00");
        assertThat(countRows("transfers")).isEqualTo(1);
    }

    @Test
    void refusedTransfersChangeNothing() throws Exception {
        mockMvc.perform(transfer("key-1", TestIbans.A, TestIbans.B, "1000.01"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        mockMvc.perform(transfer("key-2", TestIbans.A, TestIbans.D_USD, "10.00"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
        mockMvc.perform(transfer("key-3", TestIbans.A, TestIbans.UNKNOWN, "10.00"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mockMvc.perform(transfer("key-4", TestIbans.A, TestIbans.A, "10.00"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT"));

        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(TestIbans.B)).isEqualByComparingTo("0.00");
        assertThat(countRows("transfers")).isZero();
        assertThat(countRows("ledger_entries")).isZero();
    }

    /** PostgreSQL keeps microseconds, so an answer with nanoseconds would not match the row read back later. */
    @Test
    void theCreationTimeReadsBackAsItWasAnswered() throws Exception {
        String created = mockMvc.perform(transfer("key-1", TestIbans.A, TestIbans.B, "150.00"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String transferId = JsonPath.read(created, "$.id");

        String readBack = mockMvc.perform(get("/api/v1/transfers/{id}", transferId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Object answered = JsonPath.read(created, "$.createdAt");
        Object stored = JsonPath.read(readBack, "$.createdAt");
        assertThat(stored).isEqualTo(answered);
    }

    @Test
    void theAccountCanBeEmptiedExactly() throws Exception {
        mockMvc.perform(transfer("key-1", TestIbans.A, TestIbans.B, "1000.00")).andExpect(status().isCreated());

        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(TestIbans.B)).isEqualByComparingTo("1000.00");
    }

    private static RequestBuilder transfer(String idempotencyKey, String fromIban, String toIban, String amount) {
        return post("/api/v1/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromIban": "%s", "toIban": "%s", "amount": %s, "currency": "UAH", "description": "Rent"}
                        """.formatted(fromIban, toIban, amount));
    }
}
