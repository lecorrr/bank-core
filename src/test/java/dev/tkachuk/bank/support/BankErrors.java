package dev.tkachuk.bank.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/** Runs code that must be refused by a business rule and returns which rule refused it. */
public final class BankErrors {

    private BankErrors() {
    }

    /** Fails the test unless the call throws a BankException; usage: assertThat(codeOf(() -> ...)).isEqualTo(...). */
    public static ErrorCode codeOf(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).as("expected the call to be refused with a BankException").isInstanceOf(BankException.class);
        return ((BankException) thrown).code();
    }
}
