package dev.tkachuk.bank.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/** The ErrorCode a call was refused with. */
public final class BankErrors {

    private BankErrors() {
    }

    public static ErrorCode codeOf(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).as("expected the call to be refused with a BankException").isInstanceOf(BankException.class);
        return ((BankException) thrown).code();
    }
}
