package dev.tkachuk.bank.account;

import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;
import dev.tkachuk.bank.common.iban.Iban;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An account: a customer's, or one of the bank's own (see AccountKind). The balance only changes through
 * credit() and debit(), and every change must be recorded as a LedgerEntry in the same transaction.
 */
@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 34)
    private String iban;

    @Column(name = "holder_name", nullable = false, length = 140)
    private String holderName;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountKind kind;

    /** Optimistic locking: a lost update fails instead of silently overwriting a balance. */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * A new customer account with a zero balance. Taking an Iban (not a String) means the IBAN was already
     * validated. The bank's own accounts are not opened here: the V2 migration creates them.
     */
    public static Account open(Iban iban, String holderName, String currency, Instant createdAt) {
        Account account = new Account();
        account.iban = iban.value();
        account.holderName = holderName;
        account.currency = currency;
        account.balance = BigDecimal.ZERO.setScale(2);
        account.kind = AccountKind.CUSTOMER;
        account.createdAt = createdAt;
        return account;
    }

    /** False for the bank's own accounts, which the API treats as if they did not exist. */
    public boolean isCustomer() {
        return kind == AccountKind.CUSTOMER;
    }

    /** Puts money into the account. The amount must come from Amounts.requireValid. */
    public void credit(BigDecimal amount) {
        requirePositive(amount);
        balance = balance.add(amount);
    }

    /**
     * Takes money out of the account. The amount must come from Amounts.requireValid. Refuses with
     * INSUFFICIENT_FUNDS when the balance is too small, and then leaves the balance unchanged.
     */
    public void debit(BigDecimal amount) {
        requirePositive(amount);
        // compareTo, not equals: equals also compares the scale, so 100.0 and 100.00 would differ.
        if (balance.compareTo(amount) < 0) {
            throw new BankException(ErrorCode.INSUFFICIENT_FUNDS,
                    "Account " + iban + " does not have " + amount.toPlainString() + " " + currency);
        }
        balance = balance.subtract(amount);
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BankException(ErrorCode.INVALID_AMOUNT, "Amount must be greater than zero");
        }
    }
}
