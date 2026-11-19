package dev.tkachuk.bank.common;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** bank.code: the six-digit bank code (MFO) in our IBANs, checked at startup. */
@ConfigurationProperties("bank")
public record BankProperties(String code) {

    private static final Pattern BANK_CODE = Pattern.compile("[0-9]{6}");

    public BankProperties {
        if (code == null || !BANK_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("bank.code must be six digits, got " + code);
        }
    }
}
