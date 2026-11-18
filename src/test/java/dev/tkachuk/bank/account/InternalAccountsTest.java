package dev.tkachuk.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import dev.tkachuk.bank.common.BankProperties;
import dev.tkachuk.bank.support.TestAccounts;
import dev.tkachuk.bank.support.TestIbans;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** The startup check on the bank's own accounts: a wrong setup stops the application with a reason. */
@ExtendWith(MockitoExtension.class)
class InternalAccountsTest {

    private static final BankProperties BANK = new BankProperties("399999");

    @Mock
    private AccountRepository accounts;

    @Test
    void readsBothAccounts() {
        when(accounts.findByKind(AccountKind.TRANSIT)).thenReturn(Optional.of(
                TestAccounts.internal(5, TestIbans.TRANSIT, AccountKind.TRANSIT, "0.00")));
        when(accounts.findByKind(AccountKind.SETTLEMENT)).thenReturn(Optional.of(
                TestAccounts.internal(6, TestIbans.SETTLEMENT, AccountKind.SETTLEMENT, "0.00")));

        assertThat(InternalAccounts.load(accounts, BANK))
                .isEqualTo(new InternalAccounts(TestIbans.TRANSIT, TestIbans.SETTLEMENT));
    }

    @Test
    void refusesToStartWithoutThem() {
        when(accounts.findByKind(AccountKind.TRANSIT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> InternalAccounts.load(accounts, BANK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TRANSIT");
    }

    @Test
    void refusesAnAccountOfAnotherBank() {
        when(accounts.findByKind(AccountKind.TRANSIT)).thenReturn(Optional.of(
                TestAccounts.internal(5, TestIbans.PARTNER, AccountKind.TRANSIT, "0.00")));

        assertThatThrownBy(() -> InternalAccounts.load(accounts, BANK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("399999");
    }

    @Test
    void refusesAnAccountInAnotherCurrency() {
        Account inDollars = TestAccounts.internal(5, TestIbans.TRANSIT, AccountKind.TRANSIT, "0.00");
        ReflectionTestUtils.setField(inDollars, "currency", "USD");
        when(accounts.findByKind(AccountKind.TRANSIT)).thenReturn(Optional.of(inDollars));

        assertThatThrownBy(() -> InternalAccounts.load(accounts, BANK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UAH");
    }
}
