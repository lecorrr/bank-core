package dev.tkachuk.bank.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BankPropertiesTest {

    @Test
    void shouldAcceptSixDigitBankCode() {
        assertThat(new BankProperties("399999").code()).isEqualTo("399999");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"39999", "3999999", "39999A", " 399999"})
    void shouldRejectInvalidBankCode(String code) {
        assertThatThrownBy(() -> new BankProperties(code))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bank.code");
    }
}
