package dev.tkachuk.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.bank.support.IntegrationTest;
import dev.tkachuk.bank.support.TestIbans;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.RequestBuilder;

class AccountOpeningIT extends IntegrationTest {

    @Test
    void opensAnAccountWithAZeroBalance() throws Exception {
        mockMvc.perform(open("ua84 3999 9900 0000 0000 0000 0000 1", "  Artem Tkachuk  ", "UAH"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/accounts/" + TestIbans.A))
                .andExpect(jsonPath("$.iban").value(TestIbans.A))
                .andExpect(jsonPath("$.holderName").value("Artem Tkachuk"))
                .andExpect(jsonPath("$.currency").value("UAH"))
                .andExpect(jsonPath("$.balance").value(0.0));

        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("0.00");
        mockMvc.perform(get("/api/v1/accounts/{iban}", TestIbans.A))
                .andExpect(status().isOk());
    }

    @Test
    void refusesAnIbanWithWrongCheckDigits() throws Exception {
        mockMvc.perform(open("UA853999990000000000000000001", "Artem Tkachuk", "UAH"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IBAN"));

        assertThat(countCustomerAccounts()).isZero();
    }

    @Test
    void refusesASecondAccountWithTheSameIban() throws Exception {
        mockMvc.perform(open(TestIbans.A, "Artem Tkachuk", "UAH")).andExpect(status().isCreated());

        mockMvc.perform(open("ua84 3999 9900 0000 0000 0000 0000 1", "Someone Else", "USD"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));

        assertThat(countCustomerAccounts()).isEqualTo(1);
    }

    private static RequestBuilder open(String iban, String holderName, String currency) {
        return post("/api/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"iban\": \"%s\", \"holderName\": \"%s\", \"currency\": \"%s\"}"
                        .formatted(iban, holderName, currency));
    }
}
