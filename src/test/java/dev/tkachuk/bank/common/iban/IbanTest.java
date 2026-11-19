package dev.tkachuk.bank.common.iban;

import static dev.tkachuk.bank.support.BankErrors.codeOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.bank.common.error.ErrorCode;
import dev.tkachuk.bank.support.TestIbans;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IbanTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "UA213223130000026007233566001",
            "DE89370400440532013000",
            "GB82WEST12345698765432",
            "NO9386011117947",
            TestIbans.A
    })
    void shouldAcceptValidIbans(String raw) {
        assertThat(Iban.parse(raw).value()).isEqualTo(raw);
    }

    @Test
    void shouldNormaliseTypedIban() {
        Iban iban = Iban.parse(" ua21 3223 1300 0002 6007 2335 6600 1 ");

        assertThat(iban.value()).isEqualTo("UA213223130000026007233566001");
        assertThat(iban.countryCode()).isEqualTo("UA");
    }

    @Test
    void shouldTreatTwoSpellingsAsEqual() {
        Iban typed = Iban.parse("ua21 3223 1300 0002 6007 2335 6600 1");
        Iban compact = Iban.parse("UA213223130000026007233566001");

        assertThat(typed).isEqualTo(compact).hasSameHashCodeAs(compact);
    }

    @Test
    void shouldIgnoreDefaultLocale() {
        Locale saved = Locale.getDefault();
        Locale.setDefault(Locale.of("tr", "TR"));
        try {
            assertThat(Iban.parse("it60 x054 2811 1010 0000 0123 456").value())
                    .isEqualTo("IT60X0542811101000000123456");
        } finally {
            Locale.setDefault(saved);
        }
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "UA223223130000026007233566001",
            "DE89370400440532013001",
            "UA17322313000002600723356600",
            "UA6132231300000260072335660011",
            "NO698601111794",
            "DE553704004405320130000000000000000",
            "UA21-3223-1300-0002-6007-2335-6600-1",
            "1A913223130000026007233566001",
            "UAAV3223130000026007233566001",
            "UA21322313000002600723356600é",
            "UA84399999000000000000000000１"
    })
    void shouldRejectInvalidIbans(String raw) {
        assertThat(codeOf(() -> Iban.parse(raw))).isEqualTo(ErrorCode.INVALID_IBAN);
    }

    @Test
    void shouldReturnIbanOrEmptyFromTryParse() {
        assertThat(Iban.tryParse("ua84 3999 9900 0000 0000 0000 0000 1")).contains(Iban.parse(TestIbans.A));
        assertThat(Iban.tryParse("UA223223130000026007233566001")).isEmpty();
        assertThat(Iban.tryParse(null)).isEmpty();
    }

    @Test
    void shouldReadUkrainianBankCode() {
        assertThat(Iban.parse(TestIbans.A).ukrainianBankCode()).contains("399999");
        assertThat(Iban.parse(TestIbans.PARTNER).ukrainianBankCode()).contains("388888");
    }

    @Test
    void shouldHaveNoBankCodeForForeignIban() {
        assertThat(Iban.parse(TestIbans.GERMAN).ukrainianBankCode()).isEmpty();
    }
}
