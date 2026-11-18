package dev.tkachuk.bank.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.bank.common.error.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class ApiExceptionHandlerTest {

    /** The whole table, so changing a status is a deliberate edit here too. */
    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({
            "ACCOUNT_NOT_FOUND, 404",
            "TRANSFER_NOT_FOUND, 404",
            "ACCOUNT_ALREADY_EXISTS, 409",
            "CONFLICT, 409",
            "INVALID_IBAN, 400",
            "INVALID_AMOUNT, 400",
            "INVALID_PAYMENT_FILE, 400",
            "BENEFICIARY_NAME_REQUIRED, 400",
            "UNSUPPORTED_FILE_FORMAT, 415",
            "SAME_ACCOUNT, 422",
            "CURRENCY_MISMATCH, 422",
            "INSUFFICIENT_FUNDS, 422",
            "UNSUPPORTED_DESTINATION, 422",
            "IDEMPOTENCY_KEY_REUSED, 422"})
    void answersEachErrorCodeWithItsStatus(ErrorCode code, int status) {
        assertThat(ApiExceptionHandler.statusOf(code).value()).isEqualTo(status);
    }

    /** A business error is the caller's to fix; a 500 is kept for bugs. */
    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void answersEveryErrorCodeWithAClientError(ErrorCode code) {
        assertThat(ApiExceptionHandler.statusOf(code).is4xxClientError()).isTrue();
    }
}
