package dev.tkachuk.bank.account;

import static dev.tkachuk.bank.support.BankErrors.codeOf;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.bank.common.error.ErrorCode;
import dev.tkachuk.bank.support.TestAccounts;
import dev.tkachuk.bank.support.TestIbans;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccountDebitTest {

    private final Account account = TestAccounts.account(1, TestIbans.A, "UAH", "100.00");

    @Test
    void shouldDebitBalance() {
        account.debit(new BigDecimal("30.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("70.00");
    }

    @Test
    void shouldAllowDebitToZero() {
        account.debit(new BigDecimal("100.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void shouldRejectOverdraftAndKeepBalance() {
        assertThat(codeOf(() -> account.debit(new BigDecimal("100.01")))).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void shouldAllowOnlySettlementAccountBelowZero() {
        Account settlement = TestAccounts.internal(6, TestIbans.SETTLEMENT, AccountKind.SETTLEMENT, "100.00");
        Account transit = TestAccounts.internal(5, TestIbans.TRANSIT, AccountKind.TRANSIT, "100.00");

        settlement.debit(new BigDecimal("250.00"));

        assertThat(settlement.getBalance()).as("the bank's position at the National Bank").isEqualByComparingTo(
                "-150.00");
        assertThat(codeOf(() -> transit.debit(new BigDecimal("100.01")))).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        assertThat(transit.getBalance()).isEqualByComparingTo("100.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void shouldRejectNonPositiveAmount(BigDecimal amount) {
        assertThat(codeOf(() -> account.debit(amount))).isEqualTo(ErrorCode.INVALID_AMOUNT);

        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void shouldRejectMissingAmount() {
        assertThat(codeOf(() -> account.debit(null))).isEqualTo(ErrorCode.INVALID_AMOUNT);
    }
}
