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
            "NO9386011117947",                     // 15 characters, the shortest IBAN in use
            TestIbans.A
    })
    void acceptsValidIbans(String raw) {
        assertThat(Iban.parse(raw).value()).isEqualTo(raw);
    }

    @Test
    void normalisesWhatPeopleType() {
        Iban iban = Iban.parse(" ua21 3223 1300 0002 6007 2335 6600 1 ");

        assertThat(iban.value()).isEqualTo("UA213223130000026007233566001");
        assertThat(iban.countryCode()).isEqualTo("UA");
    }

    @Test
    void twoSpellingsOfOneIbanAreEqual() {
        Iban typed = Iban.parse("ua21 3223 1300 0002 6007 2335 6600 1");
        Iban compact = Iban.parse("UA213223130000026007233566001");

        assertThat(typed).isEqualTo(compact).hasSameHashCodeAs(compact);
    }

    /** With a Turkish default locale, "i".toUpperCase() is a dotted capital I, which no IBAN contains. */
    @Test
    void ignoresTheDefaultLocale() {
        Locale saved = Locale.getDefault();
        Locale.setDefault(Locale.of("tr", "TR"));
        try {
            assertThat(Iban.parse("it60 x054 2811 1010 0000 0123 456").value())
                    .isEqualTo("IT60X0542811101000000123456");
        } finally {
            Locale.setDefault(saved);
        }
    }

    /**
     * Inputs marked "good check digits" pass MOD 97, so only the rule in their comment can refuse them.
     * Without that, MOD 97 would refuse them first and the rule would go untested.
     */
    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "UA223223130000026007233566001",       // check digits do not match
            "DE89370400440532013001",              // check digits do not match
            "UA17322313000002600723356600",        // Ukrainian IBAN of 28 characters, good check digits
            "UA6132231300000260072335660011",      // Ukrainian IBAN of 30 characters, good check digits
            "NO698601111794",                      // 14 characters, good check digits
            "DE553704004405320130000000000000000", // 35 characters, good check digits
            "UA21-3223-1300-0002-6007-2335-6600-1", // only spaces are removed, never dashes
            "1A913223130000026007233566001",       // country code must be letters, good check digits
            "UAAV3223130000026007233566001",       // check digits must be digits, though MOD 97 gives 1
            "UA21322313000002600723356600é",       // letters are A to Z only
            "UA84399999000000000000000000１"   // fullwidth 1 (U+FF11) at the end: Character.digit reads it as 1
    })
    void refusesAnythingElse(String raw) {
        assertThat(codeOf(() -> Iban.parse(raw))).isEqualTo(ErrorCode.INVALID_IBAN);
    }

    @Test
    void aUkrainianIbanNamesItsBank() {
        assertThat(Iban.parse(TestIbans.A).ukrainianBankCode()).contains("399999");
        assertThat(Iban.parse(TestIbans.PARTNER).ukrainianBankCode()).contains("388888");
    }

    @Test
    void anIbanAbroadHasNoUkrainianBankCode() {
        assertThat(Iban.parse(TestIbans.GERMAN).ukrainianBankCode()).isEmpty();
    }
}
