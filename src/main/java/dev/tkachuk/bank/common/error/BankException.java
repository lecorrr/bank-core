package dev.tkachuk.bank.common.error;

import java.util.List;
import java.util.Objects;

/** A business rule failed. The code is for programs, the message for the API caller. */
public class BankException extends RuntimeException {

    private final ErrorCode code;
    private final List<String> violations;

    public BankException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public BankException(ErrorCode code, String message, List<String> violations) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.violations = List.copyOf(violations);
    }

    public ErrorCode code() {
        return code;
    }

    public List<String> violations() {
        return violations;
    }
}
