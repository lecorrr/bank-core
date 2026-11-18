package dev.tkachuk.bank.paymentfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.tkachuk.bank.support.PaymentFiles;
import dev.tkachuk.bank.support.TestIbans;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
class XmlPaymentFileReaderTest {

    private final XmlPaymentFileReader reader = new XmlPaymentFileReader();

    @Test
    void readsEveryPaymentOfAValidFile() {
        PaymentBatch batch = reader.read(PaymentFiles.open("valid-batch.xml"));

        assertThat(batch.messageId()).isEqualTo("MSG-TEST-XML-0001");
        assertThat(batch.debtorIban()).as("the debtor IBAN, not a creditor one").isEqualTo(TestIbans.A);
        assertThat(batch.payments()).hasSize(2);
        PaymentInstruction first = batch.payments().get(0);
        assertThat(first.endToEndId()).isEqualTo("E2E-1");
        assertThat(first.creditorIban()).isEqualTo(TestIbans.B);
        assertThat(first.creditorName()).isEqualTo("Olena Kovalenko");
        assertThat(first.amount()).isEqualByComparingTo("150.00");
        assertThat(first.currency()).isEqualTo("UAH");
        assertThat(first.remittanceInfo()).isEqualTo("Invoice 17");
        PaymentInstruction second = batch.payments().get(1);
        assertThat(second.creditorIban()).isEqualTo(TestIbans.C);
        assertThat(second.amount()).isEqualByComparingTo("200.50");
        assertThat(second.remittanceInfo()).isNull();
    }

    @Test
    void reportsSchemaViolationsWithTheirValues() {
        List<String> violations = violationsOf(PaymentFiles.open("invalid-schema.xml"));

        assertThat(String.join("\n", violations)).contains("GBP", "200.555");
    }

    @Test
    void checksTheTotalsInTheGroupHeader() {
        List<String> violations = violationsOf(PaymentFiles.open("wrong-totals.xml"));

        assertThat(String.join("\n", violations)).contains("NbOfTxs", "CtrlSum");
    }

    /** NbOfTxs is any positive integer to the schema, so it is compared at any size, not parsed into an int. */
    @Test
    void comparesAHeaderCountOfAnySize() {
        String hugeCount = new String(PaymentFiles.bytes("valid-batch.xml"), StandardCharsets.UTF_8)
                .replace("<NbOfTxs>2</NbOfTxs>", "<NbOfTxs>99999999999999999999</NbOfTxs>");

        assertThat(String.join("\n", violationsOf(xml(hugeCount)))).contains("NbOfTxs");
    }

    @Test
    void refusesTheSameEndToEndIdTwice() {
        assertThat(violationsOf(PaymentFiles.open("duplicate-end-to-end-id.xml")))
                .anySatisfy(violation -> assertThat(violation).contains("E2E-1"));
    }

    /** XXE: a DOCTYPE could make the parser read /etc/passwd into the message id, or call other servers. */
    @Test
    void refusesExternalEntitiesWithoutReadingThem() {
        List<String> violations = violationsOf(PaymentFiles.open("xxe-attack.xml"));

        assertThat(violations).isNotEmpty().noneSatisfy(violation -> assertThat(violation).contains("root:"));
    }

    /** Even a DOCTYPE with nothing in it: a payment file never needs one, and refusing all of them is simplest. */
    @Test
    void refusesAnyDoctype() {
        String withDoctype = new String(PaymentFiles.bytes("valid-batch.xml"), StandardCharsets.UTF_8)
                .replace("<Document ", "<!DOCTYPE Document>\n<Document ");

        assertThat(violationsOf(xml(withDoctype))).isNotEmpty();
    }

    @Test
    void refusesSomethingThatIsNotXml() {
        assertThat(violationsOf(PaymentFiles.open("not-xml.xml"))).isNotEmpty();
    }

    @Test
    void refusesBytesThatAreNotText() {
        byte[] start = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Document>".getBytes(StandardCharsets.UTF_8);
        byte[] notUtf8 = Arrays.copyOf(start, start.length + 2);
        notUtf8[start.length] = (byte) 0xFF;
        notUtf8[start.length + 1] = (byte) 0xFE;

        assertThat(violationsOf(new ByteArrayInputStream(notUtf8))).isNotEmpty();
    }

    @Test
    void refusesAnEmptyFile() {
        assertThat(violationsOf(new ByteArrayInputStream(new byte[0]))).isNotEmpty();
    }

    private static InputStream xml(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> violationsOf(InputStream content) {
        Throwable thrown = catchThrowable(() -> reader.read(content));
        assertThat(thrown).as("the file must be refused as a whole").isInstanceOf(InvalidPaymentFileException.class);
        return ((InvalidPaymentFileException) thrown).violations();
    }
}
