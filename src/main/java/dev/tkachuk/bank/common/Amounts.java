package dev.tkachuk.bank.common;

import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money amounts are BigDecimal with exactly two decimals. Never double: 0.1 + 0.2 is not 0.3 there. */
public final class Amounts {

    private Amounts() {
    }

    /**
     * Checks that an amount is positive with at most two decimal places and returns it with exactly two.
     * 150, 150.0 and 150.000 all become 150.00; 10.555 and -1 are rejected with INVALID_AMOUNT.
     */
    public static BigDecimal requireValid(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BankException(ErrorCode.INVALID_AMOUNT, "Amount must be greater than zero");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new BankException(ErrorCode.INVALID_AMOUNT,
                    "Amount can have at most two decimal places, got " + amount.toPlainString());
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
