package dev.tkachuk.bank.paymentfile;

import static dev.tkachuk.bank.support.BankErrors.codeOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.bank.common.error.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentFileFormatTest {

    @ParameterizedTest(name = "{0} sent as {1} is {2}")
    @CsvSource({
            "batch.json,        ,                         JSON",
            "BATCH.XML,         application/octet-stream, XML",
            "batch.json,        application/xml,          JSON",
            "upload,            application/json,         JSON",
            ",                  text/xml,                 XML",
            "upload,            application/vnd.bank+json, JSON"
    })
    void shouldDetectByExtensionThenContentType(String filename, String contentType, PaymentFileFormat expected) {
        assertThat(PaymentFileFormat.detect(filename, contentType)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} sent as {1} is refused")
    @CsvSource(nullValues = "NULL", value = {
            "batch.csv,         text/csv",
            "NULL,              NULL",
            "'',                application/octet-stream",
            "payments.json.exe, application/x-msdownload"
    })
    void shouldRejectUnknownFormat(String filename, String contentType) {
        assertThat(codeOf(() -> PaymentFileFormat.detect(filename, contentType)))
                .isEqualTo(ErrorCode.UNSUPPORTED_FILE_FORMAT);
    }
}
