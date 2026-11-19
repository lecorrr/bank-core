package dev.tkachuk.bank.account;

import dev.tkachuk.bank.common.BankProperties;
import dev.tkachuk.bank.common.iban.Iban;
import java.util.Optional;

/** The bank's transit and settlement accounts, read once at startup. The app won't start without them. */
public record InternalAccounts(String transitIban, String settlementIban) {

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
