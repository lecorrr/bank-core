package dev.tkachuk.bank.common.iban;

import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A validated IBAN in compact form: upper case, no spaces, e.g. UA213223130000026007233566001.
 * The constructor is private, so the only way to get an Iban is parse(), and every Iban is valid.
 */
public final class Iban {

    private static final int MIN_LENGTH = 15;
    private static final int MAX_LENGTH = 34;
    private static final int UKRAINE_LENGTH = 29;

    /**
     * Two letters (country), two check digits, then letters or digits. ASCII ranges on purpose:
     * Character.isLetter and isDigit accept every script, and Character.digit reads a fullwidth 1 as 1.
     */
    private static final Pattern SHAPE = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]+");

    private final String value;

    private Iban(String value) {
        this.value = value;
    }

    /**
     * Normalises and validates an IBAN typed by a person or sent by another system: spaces are removed,
     * letters upper-cased, and the result must have a valid shape, length and check digits.
     *
     * @throws BankException with ErrorCode.INVALID_IBAN when it is not valid
     */
    public static Iban parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid("IBAN is empty");
        }
        String compact = raw.replace(" ", "").toUpperCase(Locale.ROOT);
        if (compact.length() < MIN_LENGTH || compact.length() > MAX_LENGTH) {
            throw invalid("IBAN must be " + MIN_LENGTH + " to " + MAX_LENGTH + " characters long");
        }
        if (!SHAPE.matcher(compact).matches()) {
            throw invalid("IBAN must be two letters, two check digits, then letters A-Z or digits");
        }
        if (compact.startsWith("UA") && compact.length() != UKRAINE_LENGTH) {
            throw invalid("A Ukrainian IBAN must be " + UKRAINE_LENGTH + " characters long");
        }
        if (mod97(compact) != 1) {
            throw invalid("IBAN check digits do not match");
        }
        return new Iban(compact);
    }

    /**
     * ISO 7064 MOD 97-10 over an IBAN that already has a valid shape. The first four characters move to
     * the end and each letter stands for two digits (A = 10 ... Z = 35). That number is far too big for a long,
     * so only the remainder is kept: it stays below 97, and remainder * 100 + 35 always fits in an int.
     */
    private static int mod97(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        int remainder = 0;
        for (char c : rearranged.toCharArray()) {
            int number = Character.digit(c, 36);
            int shift = number < 10 ? 10 : 100;
            remainder = (remainder * shift + number) % 97;
        }
        return remainder;
    }

    private static BankException invalid(String why) {
        return new BankException(ErrorCode.INVALID_IBAN, why);
    }

    public String value() {
        return value;
    }

    public String countryCode() {
        return value.substring(0, 2);
    }

    /**
     * The bank's code in a Ukrainian IBAN, its MFO: the six digits after the check digits, which tell whether a
     * transfer stays in this bank or goes to another one. Empty for other countries, whose IBANs differ in layout.
     */
    public Optional<String> ukrainianBankCode() {
        return countryCode().equals("UA") ? Optional.of(value.substring(4, 10)) : Optional.empty();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Iban iban && value.equals(iban.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
