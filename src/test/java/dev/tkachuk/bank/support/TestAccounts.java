package dev.tkachuk.bank.support;

import dev.tkachuk.bank.account.Account;
import dev.tkachuk.bank.account.AccountKind;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** Accounts for unit tests, built without JPA. */
public final class TestAccounts {

    public static final String CUSTOMER = "C1001";

    private TestAccounts() {
    }

    public static Account account(long id, String iban, String currency, String balance) {
        Account account = BeanUtils.instantiateClass(Account.class);
        ReflectionTestUtils.setField(account, "id", id);
        ReflectionTestUtils.setField(account, "iban", iban);
        ReflectionTestUtils.setField(account, "holderName", "Holder of " + iban);
        ReflectionTestUtils.setField(account, "currency", currency);
        ReflectionTestUtils.setField(account, "balance", new BigDecimal(balance));
        ReflectionTestUtils.setField(account, "kind", AccountKind.CUSTOMER);
        ReflectionTestUtils.setField(account, "customerId", CUSTOMER);
        ReflectionTestUtils.setField(account, "createdAt", Instant.parse("2026-11-01T09:00:00Z"));
        return account;
    }

    public static Account internal(long id, String iban, AccountKind kind, String balance) {
        Account account = account(id, iban, "UAH", balance);
        ReflectionTestUtils.setField(account, "holderName", kind + " account");
        ReflectionTestUtils.setField(account, "kind", kind);
        ReflectionTestUtils.setField(account, "customerId", null);
        return account;
    }
}
