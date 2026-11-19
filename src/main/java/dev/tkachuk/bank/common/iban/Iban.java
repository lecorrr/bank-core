package dev.tkachuk.bank.common.iban;

import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** A validated IBAN in compact form (upper case, no spaces). Only parse() and tryParse() create one. */
public final class Iban {

    private static final int MIN_LENGTH = 15;
    private static final int MAX_LENGTH = 34;
    private static final int UKRAINE_LENGTH = 29;

    // ASCII ranges on purpose: Character.isLetter and isDigit accept any script.
    private static final Pattern SHAPE = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]+");

    private final String value;

    private Iban(String value) {
        this.value = value;
    }

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

    public static Optional<Iban> tryParse(String raw) {
        try {
            return Optional.of(parse(raw));
        } catch (BankException e) {
            return Optional.empty();
        }
    }

    // ISO 7064 MOD 97-10, keeping only the remainder so the number never overflows.
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

    /** The six-digit bank code (MFO) after the check digits; empty for IBANs of other countries. */
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
