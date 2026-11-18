package dev.tkachuk.bank.common.error;

import java.util.List;
import java.util.Objects;

/** A business rule said no. The code says which rule; the message says why, for the API caller. */
public class BankException extends RuntimeException {

    private final ErrorCode code;
    private final List<String> violations;

    public BankException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    /** @param violations every problem found when a whole input was checked, each naming its field */
    public BankException(ErrorCode code, String message, List<String> violations) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.violations = List.copyOf(violations);
    }

    public ErrorCode code() {
        return code;
    }

    /** Empty unless a whole input (a payment file, say) was checked and several problems were found. */
    public List<String> violations() {
        return violations;
    }
}
