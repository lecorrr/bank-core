package dev.tkachuk.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.bank.support.IntegrationTest;
import dev.tkachuk.bank.support.TestIbans;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Reading accounts and depositing money, through HTTP and a real database. */
class AccountFlowIT extends IntegrationTest {

    @Test
    void returnsAnExistingAccount() throws Exception {
        insertAccount(TestIbans.A, "Artem Demo", "UAH", "1000.00");

        mockMvc.perform(get("/api/v1/accounts/{iban}", TestIbans.A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iban").value(TestIbans.A))
                .andExpect(jsonPath("$.holderName").value("Artem Demo"))
                .andExpect(jsonPath("$.currency").value("UAH"))
                .andExpect(jsonPath("$.balance").value(1000.0));
    }

    @Test
    void lookupAcceptsLowerCase() throws Exception {
        insertAccount(TestIbans.A, "Artem Demo", "UAH", "1000.00");

        mockMvc.perform(get("/api/v1/accounts/{iban}", TestIbans.A.toLowerCase(Locale.ROOT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iban").value(TestIbans.A));
    }

    @Test
    void unknownAccountIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/{iban}", TestIbans.UNKNOWN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void depositChangesTheBalanceAndAppearsOnTheStatement() throws Exception {
        insertAccount(TestIbans.A, "Artem Demo", "UAH", "1000.00");

        mockMvc.perform(post("/api/v1/accounts/{iban}/deposits", TestIbans.A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 250.50, \"description\": \"Salary\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1250.5));

        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("1250.50");
        mockMvc.perform(get("/api/v1/accounts/{iban}/statement", TestIbans.A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1250.5))
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].type").value("DEPOSIT"))
                .andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.entries[0].amount").value(250.5))
                .andExpect(jsonPath("$.entries[0].balanceAfter").value(1250.5))
                .andExpect(jsonPath("$.entries[0].description").value("Salary"))
                // A deposit belongs to no transfer (null or absent both pass doesNotExist).
                .andExpect(jsonPath("$.entries[0].transferId").doesNotExist());
    }

    @Test
    void depositWithoutDescriptionGetsADefaultOne() throws Exception {
        insertAccount(TestIbans.A, "Artem Demo", "UAH", "0.00");

        mockMvc.perform(post("/api/v1/accounts/{iban}/deposits", TestIbans.A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 10}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/accounts/{iban}/statement", TestIbans.A))
                .andExpect(jsonPath("$.entries[0].description").value("Cash deposit"));
    }

    @Test
    void depositWithThreeDecimalsIsRefusedAndChangesNothing() throws Exception {
        insertAccount(TestIbans.A, "Artem Demo", "UAH", "1000.00");

        mockMvc.perform(post("/api/v1/accounts/{iban}/deposits", TestIbans.A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 10.555}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));

        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("1000.00");
        assertThat(countRows("ledger_entries")).isZero();
    }

    @Test
    void depositToAnUnknownAccountIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{iban}/deposits", TestIbans.UNKNOWN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 10}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        assertThat(countRows("ledger_entries")).isZero();
    }

    @Test
    void accountsAreListedInTheOrderTheyWereOpened() throws Exception {
        insertAccount(TestIbans.B, "Olena Kovalenko", "UAH", "0.00");
        insertAccount(TestIbans.A, "Artem Demo", "UAH", "0.00");

        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].iban").value(TestIbans.B))
                .andExpect(jsonPath("$[1].iban").value(TestIbans.A));
    }
}
