package dev.tkachuk.bank.account;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;
import dev.tkachuk.bank.support.TestAccounts;
import dev.tkachuk.bank.support.TestIbans;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountsController.class)
@Import(AccountMapperImpl.class)
class AccountsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    @Test
    void shouldReturnAccountAsJson() throws Exception {
        when(accountService.get(TestIbans.A)).thenReturn(TestAccounts.account(1, TestIbans.A, "UAH", "850.00"));

        mockMvc.perform(get("/api/v1/accounts/{iban}", TestIbans.A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iban").value(TestIbans.A))
                .andExpect(jsonPath("$.currency").value("UAH"))
                .andExpect(jsonPath("$.balance").value(850.0))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void shouldListAccounts() throws Exception {
        when(accountService.list()).thenReturn(List.of(
                TestAccounts.account(1, TestIbans.A, "UAH", "1.00"),
                TestAccounts.account(2, TestIbans.B, "UAH", "2.00")));

        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].iban").value(TestIbans.B));
    }

    @Test
    void shouldReturn404ProblemForUnknownAccount() throws Exception {
        when(accountService.get(anyString()))
                .thenThrow(new BankException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found"));

        mockMvc.perform(get("/api/v1/accounts/{iban}", TestIbans.UNKNOWN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.type").value("urn:bank-core:error:account-not-found"))
                .andExpect(jsonPath("$.title").value("Account not found"));
    }

    @Test
    void shouldReturn400ProblemForInvalidIban() throws Exception {
        when(accountService.get(anyString()))
                .thenThrow(new BankException(ErrorCode.INVALID_IBAN, "IBAN check digits do not match"));

        mockMvc.perform(get("/api/v1/accounts/{iban}", TestIbans.UNKNOWN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IBAN"))
                .andExpect(jsonPath("$.title").value("Invalid IBAN"));
    }

    @Test
    void shouldRejectDepositBelowOneCentBeforeService() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/{iban}/deposits", TestIbans.A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": -5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations", hasSize(1)));

        verifyNoInteractions(accountService);
    }

    @Test
    void shouldRequireEveryFieldToOpenAccount() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holderName\": \"Artem\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations", hasSize(2)));

        verifyNoInteractions(accountService);
    }

    @Test
    void shouldReturn400ForUnknownCurrency() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"iban\": \"%s\", \"holderName\": \"Artem\", \"currency\": \"GBP\"}"
                                .formatted(TestIbans.A)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(accountService);
    }

    @Test
    void shouldKeepErrorCodeOfBusinessErrors() throws Exception {
        when(accountService.deposit(anyString(), any(), anyString()))
                .thenThrow(new BankException(ErrorCode.INVALID_AMOUNT, "At most two decimal places"));

        mockMvc.perform(post("/api/v1/accounts/{iban}/deposits", TestIbans.A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 10.555}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"))
                .andExpect(jsonPath("$.detail").value("At most two decimal places"));
    }
}
