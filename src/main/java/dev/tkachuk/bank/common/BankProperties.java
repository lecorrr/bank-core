package dev.tkachuk.bank.common;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who this bank is, from the "bank" section of application.yml. Checked when the application starts, so a typo
 * stops it there instead of sending every transfer to the wrong place.
 *
 * @param code the bank's code (MFO): the six digits after the check digits in each of its IBANs. A transfer to
 *             an IBAN with another code goes to that bank.
 */
@ConfigurationProperties("bank")
public record BankProperties(String code) {

    private static final Pattern BANK_CODE = Pattern.compile("[0-9]{6}");

    public BankProperties {
        if (code == null || !BANK_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("bank.code must be six digits, got " + code);
        }
    }
}
