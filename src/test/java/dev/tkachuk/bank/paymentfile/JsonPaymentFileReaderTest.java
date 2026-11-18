package dev.tkachuk.bank.paymentfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.tkachuk.bank.support.PaymentFiles;
import dev.tkachuk.bank.support.TestIbans;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
class JsonPaymentFileReaderTest {

    private final JsonPaymentFileReader reader = new JsonPaymentFileReader();

    @Test
    void readsEveryPaymentOfAValidFile() {
        PaymentBatch batch = reader.read(PaymentFiles.open("valid-batch.json"));

        assertThat(batch.messageId()).isEqualTo("MSG-TEST-JSON-0001");
        assertThat(batch.debtorIban()).isEqualTo(TestIbans.A);
        assertThat(batch.payments()).hasSize(2);
        PaymentInstruction first = batch.payments().get(0);
        assertThat(first.endToEndId()).isEqualTo("E2E-1");
        assertThat(first.creditorIban()).isEqualTo(TestIbans.B);
        assertThat(first.creditorName()).isEqualTo("Olena Kovalenko");
        assertThat(first.amount()).isEqualByComparingTo("150.00");
        assertThat(first.currency()).isEqualTo("UAH");
        assertThat(first.remittanceInfo()).isEqualTo("Invoice 17");
        PaymentInstruction second = batch.payments().get(1);
        assertThat(second.endToEndId()).isEqualTo("E2E-2");
        // Read as the decimal written in the file, never through a double.
        assertThat(second.amount()).isEqualByComparingTo("200.50");
        assertThat(second.remittanceInfo()).isNull();
    }

    @Test
    void keepsEachAmountExactlyAsWritten() {
        PaymentBatch batch = reader.read(PaymentFiles.open("valid-batch.json"));

        assertThat(batch.payments().get(0).amount().toPlainString()).isEqualTo("150.00");
        assertThat(batch.payments().get(1).amount().toPlainString()).isEqualTo("200.50");
    }

    @Test
    void reportsEverySchemaViolationAtOnce() {
        List<String> violations = violationsOf(PaymentFiles.open("invalid-schema.json"));

        assertThat(violations).hasSizeGreaterThanOrEqualTo(5);
        assertThat(String.join("\n", violations))
                .as("each violation names its field")
                .contains("priority", "creditorIban", "amount", "creditorName", "currency");
    }

    /** The XML schema refuses this too: a third decimal would have to be rounded away, and money is never rounded. */
    @Test
    void refusesAnAmountWithMoreThanTwoDecimals() {
        String thirdDecimal = """
                {"messageId": "MSG-1", "debtorIban": "%s", "payments": [
                  {"endToEndId": "E2E-1", "creditorIban": "%s", "creditorName": "Olena Kovalenko",
                   "amount": 150.00, "currency": "UAH"},
                  {"endToEndId": "E2E-2", "creditorIban": "%s", "creditorName": "Olena Kovalenko",
                   "amount": 10.555, "currency": "UAH"}]}
                """.formatted(TestIbans.A, TestIbans.B, TestIbans.B);

        assertThat(violationsOf(json(thirdDecimal)))
                .containsExactly("$.payments[1].amount: 10.555 has more than two decimals");
    }

    @Test
    void acceptsTrailingZerosAfterTheSecondDecimal() {
        String trailingZero = """
                {"messageId": "MSG-1", "debtorIban": "%s", "payments": [
                  {"endToEndId": "E2E-1", "creditorIban": "%s", "creditorName": "Olena Kovalenko",
                   "amount": 10.500, "currency": "UAH"}]}
                """.formatted(TestIbans.A, TestIbans.B);

        assertThat(reader.read(json(trailingZero)).payments().getFirst().amount()).isEqualByComparingTo("10.50");
    }

    @Test
    void refusesTheSameEndToEndIdTwice() {
        assertThat(violationsOf(PaymentFiles.open("duplicate-end-to-end-id.json")))
                .anySatisfy(violation -> assertThat(violation).contains("E2E-1"));
    }

    /** "The last one wins" would let a file say two amounts for one payment. */
    @Test
    void refusesAFieldGivenTwice() {
        String amountTwice = """
                {"messageId": "MSG-1", "debtorIban": "%s", "payments": [
                  {"endToEndId": "E2E-1", "creditorIban": "%s", "creditorName": "Olena Kovalenko",
                   "amount": 150.00, "amount": 15000.00, "currency": "UAH"}]}
                """.formatted(TestIbans.A, TestIbans.B);

        assertThat(violationsOf(json(amountTwice))).anySatisfy(violation -> assertThat(violation).contains("amount"));
    }

    @Test
    void refusesAnythingAfterTheDocument() {
        String validThenMore = new String(PaymentFiles.bytes("valid-batch.json"), StandardCharsets.UTF_8)
                + "{\"messageId\": \"MSG-2\"}";

        assertThat(violationsOf(json(validThenMore))).isNotEmpty();
    }

    @Test
    void refusesBytesThatAreNotText() {
        byte[] notUtf8 = {'{', '"', 'm', '"', ':', '"', (byte) 0xFF, (byte) 0xFE, '"', '}'};

        assertThat(violationsOf(new ByteArrayInputStream(notUtf8))).isNotEmpty();
    }

    @Test
    void refusesSomethingThatIsNotJson() {
        assertThat(violationsOf(PaymentFiles.open("not-json.json"))).isNotEmpty();
    }

    @Test
    void refusesAnEmptyFile() {
        assertThat(violationsOf(new ByteArrayInputStream(new byte[0]))).isNotEmpty();
    }

    @Test
    void refusesAFileWithoutPayments() {
        String noPayments = """
                {"messageId": "MSG-1", "debtorIban": "%s", "payments": []}
                """.formatted(TestIbans.A);

        assertThat(violationsOf(json(noPayments))).anySatisfy(violation -> assertThat(violation).contains("payments"));
    }

    private static InputStream json(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> violationsOf(InputStream content) {
        Throwable thrown = catchThrowable(() -> reader.read(content));
        assertThat(thrown).as("the file must be refused as a whole").isInstanceOf(InvalidPaymentFileException.class);
        return ((InvalidPaymentFileException) thrown).violations();
    }
}
