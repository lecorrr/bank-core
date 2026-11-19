package dev.tkachuk.bank.transfer;

import dev.tkachuk.bank.account.Account;
import dev.tkachuk.bank.account.AccountRepository;
import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;
import java.util.Optional;

/** Locks two accounts, the smaller IBAN first, so transfers in opposite directions can't deadlock. */
record LockedAccounts(Account from, Account to) {

    static LockedAccounts lock(AccountRepository accounts, String fromIban, String toIban) {
        boolean fromIsSmaller = fromIban.compareTo(toIban) < 0;
        Account first = locked(accounts, fromIsSmaller ? fromIban : toIban);
        Account second = locked(accounts, fromIsSmaller ? toIban : fromIban);
        return fromIsSmaller ? new LockedAccounts(first, second) : new LockedAccounts(second, first);
    }

    static Found lockIfPresent(AccountRepository accounts, String fromIban, String toIban) {
        boolean fromIsSmaller = fromIban.compareTo(toIban) < 0;
        Optional<Account> first = accounts.findByIbanForUpdate(fromIsSmaller ? fromIban : toIban);
        Optional<Account> second = accounts.findByIbanForUpdate(fromIsSmaller ? toIban : fromIban);
        return fromIsSmaller ? new Found(first, second) : new Found(second, first);
    }

    record Found(Optional<Account> from, Optional<Account> to) {
    }

    private static Account locked(AccountRepository accounts, String iban) {
        return accounts.findByIbanForUpdate(iban).orElseThrow(() ->
                new BankException(ErrorCode.ACCOUNT_NOT_FOUND, "Account " + iban + " not found"));
    }
}
