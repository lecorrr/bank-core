package dev.tkachuk.bank.common;

import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money is BigDecimal with exactly two decimals, never double. */
public final class Amounts {

    private Amounts() {
    }

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
