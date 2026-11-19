package dev.tkachuk.bank.paymentfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.bank.support.IntegrationTest;
import dev.tkachuk.bank.support.PaymentFiles;
import dev.tkachuk.bank.support.TestIbans;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.RequestBuilder;

class PaymentBatchIT extends IntegrationTest {

    @BeforeEach
    void openAccounts() {
        insertAccount(TestIbans.A, "Artem Demo", "UAH", "1000.00");
        insertAccount(TestIbans.B, "Olena Kovalenko", "UAH", "0.00");
        insertAccount(TestIbans.C, "Supplier LLC", "UAH", "0.00");
    }

    @Test
    void shouldPayEveryInstructionOfJsonFile() throws Exception {
        mockMvc.perform(upload("valid-batch.json", "application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value("MSG-TEST-JSON-0001"))
                .andExpect(jsonPath("$.format").value("JSON"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejected").value(0))
                .andExpect(jsonPath("$.items[*].status", everyItem(is("ACCEPTED"))))
                .andExpect(jsonPath("$.items[*].replayed", everyItem(is(false))));

        assertPaid();
    }

    @Test
    void shouldPayEveryInstructionOfXmlFile() throws Exception {
        mockMvc.perform(upload("valid-batch.xml", "application/xml"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value("MSG-TEST-XML-0001"))
                .andExpect(jsonPath("$.format").value("XML"))
                .andExpect(jsonPath("$.accepted").value(2));

        assertPaid();
    }

    @Test
    void shouldNotPayTwiceWhenFileIsUploadedAgain() throws Exception {
        mockMvc.perform(upload("valid-batch.json", "application/json")).andExpect(status().isOk());

        mockMvc.perform(upload("valid-batch.json", "application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.items[*].replayed", everyItem(is(true))));

        assertPaid();
    }

    @Test
    void shouldRejectUnfundedPaymentAndPayTheOthers() throws Exception {
        jdbc.update("UPDATE accounts SET balance = 160.00 WHERE iban = ?", TestIbans.A);

        mockMvc.perform(upload("valid-batch.json", "application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[1].status").value("REJECTED"))
                .andExpect(jsonPath("$.items[1].errorCode").value("INSUFFICIENT_FUNDS"));

        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("10.00");
        assertThat(balanceOf(TestIbans.B)).isEqualByComparingTo("150.00");
        assertThat(balanceOf(TestIbans.C)).isEqualByComparingTo("0.00");
    }

    @Test
    void shouldRejectInvalidFileAsAWhole() throws Exception {
        mockMvc.perform(upload("invalid-schema.json", "application/json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_FILE"))
                .andExpect(jsonPath("$.violations", hasSize(greaterThanOrEqualTo(5))));

        assertThat(countRows("transfers")).isZero();
    }

    @Test
    void shouldRejectJsonAmountWithThreeDecimals() throws Exception {
        mockMvc.perform(upload("three-decimals.json", "application/json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_FILE"))
                .andExpect(jsonPath("$.violations", hasSize(1)));

        assertThat(countRows("transfers")).isZero();
    }

    @Test
    void shouldRejectXmlWithExternalEntities() throws Exception {
        mockMvc.perform(upload("xxe-attack.xml", "application/xml"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_FILE"));

        assertThat(countRows("transfers")).isZero();
    }

    @Test
    void shouldRejectOtherFileTypes() throws Exception {
        MockMultipartFile csv = new MockMultipartFile("file", "payments.csv", "text/csv",
                "E2E-1;150.00".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/payment-batches").file(csv))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_FORMAT"));

        assertThat(countRows("transfers")).isZero();
    }

    @Test
    void shouldRequireFile() throws Exception {
        mockMvc.perform(multipart("/api/v1/payment-batches"))
                .andExpect(status().isBadRequest());
    }

    private void assertPaid() {
        assertThat(balanceOf(TestIbans.A)).isEqualByComparingTo("649.50");
        assertThat(balanceOf(TestIbans.B)).isEqualByComparingTo("150.00");
        assertThat(balanceOf(TestIbans.C)).isEqualByComparingTo("200.50");
        assertThat(countRows("transfers")).isEqualTo(2);
        assertThat(countRows("ledger_entries")).isEqualTo(4);
    }

    private static RequestBuilder upload(String fileName, String contentType) {
        return multipart("/api/v1/payment-batches")
                .file(new MockMultipartFile("file", fileName, contentType, PaymentFiles.bytes(fileName)));
    }
}
