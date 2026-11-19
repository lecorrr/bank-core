package dev.tkachuk.bank.paymentfile;

import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;
import java.util.List;

public class InvalidPaymentFileException extends BankException {

    public InvalidPaymentFileException(List<String> violations) {
        super(ErrorCode.INVALID_PAYMENT_FILE, describe(violations), violations);
    }

    private static String describe(List<String> violations) {
        if (violations == null || violations.isEmpty()) {
            throw new IllegalArgumentException("An invalid payment file needs at least one violation");
        }
        return "The payment file is invalid: " + violations.size() + " problem(s) found";
    }
}
