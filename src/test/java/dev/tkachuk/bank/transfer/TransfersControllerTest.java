package dev.tkachuk.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;
import dev.tkachuk.bank.support.TestIbans;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP layer of transfers with the service mocked. */
@WebMvcTest(TransfersController.class)
@Import(TransferMapperImpl.class)
class TransfersControllerTest {

    private static final UUID TRANSFER_ID = UUID.fromString("0b6f4c1e-5d1a-4f7e-9a51-2f3c8d9e1a20");
    private static final String VALID_BODY = """
            {"fromIban": "%s", "toIban": "%s", "amount": 150.00, "currency": "UAH", "description": "Rent"}
            """.formatted(TestIbans.A, TestIbans.B);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferService transferService;

    @Test
    void newTransferIsCreatedWithItsLocation() throws Exception {
        when(transferService.transfer(any())).thenReturn(new TransferResult(transfer("key-1"), false));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/transfers/" + TRANSFER_ID))
                .andExpect(header().doesNotExist(TransfersController.REPLAYED_HEADER))
                .andExpect(jsonPath("$.id").value(TRANSFER_ID.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value(150.0))
                .andExpect(jsonPath("$.currency").value("UAH"));
    }

    @Test
    void theRequestReachesTheServiceUnchanged() throws Exception {
        when(transferService.transfer(any())).thenReturn(new TransferResult(transfer("key-1"), false));

        mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY));

        ArgumentCaptor<TransferCommand> command = ArgumentCaptor.forClass(TransferCommand.class);
        verify(transferService).transfer(command.capture());
        assertThat(command.getValue().idempotencyKey()).isEqualTo("key-1");
        assertThat(command.getValue().fromIban()).isEqualTo(TestIbans.A);
        assertThat(command.getValue().toIban()).isEqualTo(TestIbans.B);
        assertThat(command.getValue().amount()).isEqualByComparingTo("150.00");
        assertThat(command.getValue().currency()).isEqualTo("UAH");
        assertThat(command.getValue().description()).isEqualTo("Rent");
    }

    @Test
    void replayAnswersOkAndSaysSo() throws Exception {
        when(transferService.transfer(any())).thenReturn(new TransferResult(transfer("key-1"), true));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string(TransfersController.REPLAYED_HEADER, "true"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.id").value(TRANSFER_ID.toString()));
    }

    @Test
    void idempotencyKeyIsRequired() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(transferService);
    }

    @Test
    void invalidBodyListsEveryProblem() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromIban\": \"%s\", \"toIban\": \"%s\", \"amount\": 0, \"currency\": \"UAH\"}"
                                .formatted(TestIbans.A, TestIbans.B)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations", hasSize(2)));

        verifyNoInteractions(transferService);
    }

    @Test
    void unknownCurrencyIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("UAH", "GBP")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(transferService);
    }

    @Test
    void businessErrorsBecomeProblemDetails() throws Exception {
        when(transferService.transfer(any()))
                .thenThrow(new BankException(ErrorCode.INSUFFICIENT_FUNDS, "Balance 10.00 is less than 150.00"));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().is(422))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.type").value("urn:bank-core:error:insufficient-funds"))
                .andExpect(jsonPath("$.title").value("Insufficient funds"))
                .andExpect(jsonPath("$.detail").value("Balance 10.00 is less than 150.00"));
    }

    @Test
    void aTransferToAnotherBankCarriesThePayeesName() throws Exception {
        Transfer pending = Transfer.pending(new TransferCommand("key-2", TestIbans.A, TestIbans.PARTNER,
                "Olena Kovalenko", new BigDecimal("150.00"), "UAH", "Rent"), Instant.parse("2026-11-09T10:00:00Z"));
        ReflectionTestUtils.setField(pending, "id", TRANSFER_ID);
        when(transferService.transfer(any())).thenReturn(new TransferResult(pending, false));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "key-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace(TestIbans.B, TestIbans.PARTNER)
                                .replace("\"amount\"", "\"toName\": \"Olena Kovalenko\", \"amount\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.toName").value("Olena Kovalenko"));

        ArgumentCaptor<TransferCommand> command = ArgumentCaptor.forClass(TransferCommand.class);
        verify(transferService).transfer(command.capture());
        assertThat(command.getValue().toName()).isEqualTo("Olena Kovalenko");
        assertThat(command.getValue().toIban()).isEqualTo(TestIbans.PARTNER);
    }

    @Test
    void aMissingPayeeNameForAnotherBankIsABadRequest() throws Exception {
        when(transferService.transfer(any())).thenThrow(new BankException(ErrorCode.BENEFICIARY_NAME_REQUIRED,
                "A transfer to another bank needs the payee's name (toName)"));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace(TestIbans.B, TestIbans.PARTNER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BENEFICIARY_NAME_REQUIRED"))
                .andExpect(jsonPath("$.title").value("Beneficiary name required"));
    }

    @Test
    void aTransferAbroadIsUnprocessable() throws Exception {
        when(transferService.transfer(any())).thenThrow(new BankException(ErrorCode.UNSUPPORTED_DESTINATION,
                "Transfers to banks outside Ukraine are not supported"));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace(TestIbans.B, TestIbans.GERMAN)))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_DESTINATION"));
    }

    @Test
    void aDescriptionOfSpacesOnlyIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("\"Rent\"", "\"   \"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(transferService);
    }

    @Test
    void returnsATransferById() throws Exception {
        when(transferService.get(TRANSFER_ID)).thenReturn(transfer("key-1"));

        mockMvc.perform(get("/api/v1/transfers/{id}", TRANSFER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromIban").value(TestIbans.A))
                .andExpect(jsonPath("$.toIban").value(TestIbans.B))
                .andExpect(jsonPath("$.description").value("Rent"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void unknownTransferIsNotFound() throws Exception {
        when(transferService.get(any()))
                .thenThrow(new BankException(ErrorCode.TRANSFER_NOT_FOUND, "Transfer not found"));

        mockMvc.perform(get("/api/v1/transfers/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void transferIdMustBeAUuid() throws Exception {
        mockMvc.perform(get("/api/v1/transfers/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(transferService);
    }

    @Test
    void unexpectedErrorsDoNotLeakInternals() throws Exception {
        when(transferService.get(any())).thenThrow(new IllegalStateException("connection pool secret-host:5432"));

        mockMvc.perform(get("/api/v1/transfers/{id}", TRANSFER_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("secret-host"))));
    }

    private static Transfer transfer(String idempotencyKey) {
        TransferCommand command = new TransferCommand(idempotencyKey, TestIbans.A, TestIbans.B,
                new BigDecimal("150.00"), "UAH", "Rent");
        Transfer transfer = Transfer.completed(command, Instant.parse("2026-11-09T10:00:00Z"));
        // The database assigns the id on save; here there is no database.
        ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
        return transfer;
    }
}
