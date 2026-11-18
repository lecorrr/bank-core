package dev.tkachuk.bank.account;

import dev.tkachuk.bank.common.BankProperties;
import dev.tkachuk.bank.common.iban.Iban;
import java.util.Optional;

/**
 * The IBANs of the bank's own accounts that a transfer to another bank passes through. The V2 migration creates
 * them and they never change, so they are read once when the application starts (see AccountConfiguration), and
 * the application refuses to start without them.
 *
 * @param transitIban    where a transfer's money waits for the other bank's answer
 * @param settlementIban where it goes once the other bank has accepted the transfer
 */
public record InternalAccounts(String transitIban, String settlementIban) {

    /**
     * Reads both accounts and checks them: each must exist, be in hryvnia (the interbank system settles nothing
     * else) and belong to this bank.
     *
     * @throws IllegalStateException naming the account and what is wrong with it
     */
    public static InternalAccounts load(AccountRepository accounts, BankProperties bank) {
        return new InternalAccounts(ibanOf(accounts, AccountKind.TRANSIT, bank),
                ibanOf(accounts, AccountKind.SETTLEMENT, bank));
    }

    private static String ibanOf(AccountRepository accounts, AccountKind kind, BankProperties bank) {
        Account account = accounts.findByKind(kind).orElseThrow(() -> new IllegalStateException(
                "There is no " + kind + " account: it comes with the V2 database migration"));
        if (!account.getCurrency().equals("UAH")) {
            throw new IllegalStateException("The " + kind + " account " + account.getIban() + " must be in UAH, not "
                    + account.getCurrency());
        }
        Optional<String> bankCode = Iban.parse(account.getIban()).ukrainianBankCode();
        if (!bankCode.equals(Optional.of(bank.code()))) {
            throw new IllegalStateException("The " + kind + " account " + account.getIban()
                    + " does not belong to bank " + bank.code() + " (bank.code in application.yml)");
        }
        return account.getIban();
    }
}
