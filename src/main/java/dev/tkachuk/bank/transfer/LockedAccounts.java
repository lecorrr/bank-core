package dev.tkachuk.bank.transfer;

import dev.tkachuk.bank.account.Account;
import dev.tkachuk.bank.account.AccountRepository;
import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;

/**
 * The two accounts of one movement of money, locked (SELECT ... FOR UPDATE) until the transaction ends. The smaller
 * IBAN is always locked first, whatever the direction: A-to-B and B-to-A at the same moment would otherwise each lock
 * one row and wait for the other, a deadlock. Every movement between two accounts locks through here, so they all
 * keep the same order.
 */
record LockedAccounts(Account from, Account to) {

    static LockedAccounts lock(AccountRepository accounts, String fromIban, String toIban) {
        boolean fromIsSmaller = fromIban.compareTo(toIban) < 0;
        Account first = locked(accounts, fromIsSmaller ? fromIban : toIban);
        Account second = locked(accounts, fromIsSmaller ? toIban : fromIban);
        return fromIsSmaller ? new LockedAccounts(first, second) : new LockedAccounts(second, first);
    }

    private static Account locked(AccountRepository accounts, String iban) {
        return accounts.findByIbanForUpdate(iban).orElseThrow(() ->
                new BankException(ErrorCode.ACCOUNT_NOT_FOUND, "Account " + iban + " not found"));
    }
}
