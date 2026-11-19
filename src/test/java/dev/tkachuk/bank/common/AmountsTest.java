package dev.tkachuk.bank.common;

import static dev.tkachuk.bank.support.BankErrors.codeOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.bank.common.error.ErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AmountsTest {

    @ParameterizedTest(name = "{0} becomes {1}")
    @CsvSource({"150, 150.00", "150.0, 150.00", "150.000, 150.00", "0.01, 0.01", "1000000000, 1000000000.00"})
    void shouldScaleValidAmountsToTwoDecimals(BigDecimal amount, String expected) {
        BigDecimal valid = Amounts.requireValid(amount);

        assertThat(valid.toPlainString()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} is refused")
    @NullSource
    @ValueSource(strings = {"0", "0.00", "-1", "-0.01", "10.555", "0.001"})
    void shouldRejectZeroNegativeAndFractionsOfACent(BigDecimal amount) {
        assertThat(codeOf(() -> Amounts.requireValid(amount))).isEqualTo(ErrorCode.INVALID_AMOUNT);
    }
}
