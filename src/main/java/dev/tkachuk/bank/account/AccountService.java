package dev.tkachuk.bank.account;

import dev.tkachuk.bank.common.Amounts;
import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;
import dev.tkachuk.bank.common.iban.Iban;
import dev.tkachuk.bank.ledger.EntryType;
import dev.tkachuk.bank.ledger.LedgerEntry;
import dev.tkachuk.bank.ledger.LedgerEntryRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accounts;
    private final LedgerEntryRepository ledgerEntries;
    private final Clock clock;

    /** Opens a customer account; customerId may be null while the customer is not known yet. */
    @Transactional
    public Account open(String rawIban, String holderName, String currency, String customerId) {
        Iban iban = Iban.parse(rawIban);
        if (accounts.existsByIban(iban.value())) {
            throw new BankException(ErrorCode.ACCOUNT_ALREADY_EXISTS, "Account " + iban + " already exists");
        }
        return accounts.save(Account.open(iban, holderName.strip(), currency, customerId, clock.instant()));
    }

    public List<Account> list() {
        return accounts.findAllByKindOrderByIdAsc(AccountKind.CUSTOMER);
    }

    public Account get(String iban) {
        String compact = compact(iban);
        return accounts.findByIban(compact).filter(Account::isCustomer).orElseThrow(() -> accountNotFound(compact));
    }

    @Transactional
    public Account deposit(String iban, BigDecimal amount, String description) {
        BigDecimal validAmount = Amounts.requireValid(amount);
        String compact = compact(iban);
        Account account = accounts.findByIbanForUpdate(compact).filter(Account::isCustomer)
                .orElseThrow(() -> accountNotFound(compact));
        account.credit(validAmount);
        ledgerEntries.save(LedgerEntry.credit(account.getId(), validAmount, account.getBalance(),
                EntryType.DEPOSIT, null, description, clock.instant()));
        return account;
    }

    public List<LedgerEntry> statement(String iban) {
        return ledgerEntries.findByAccountIdOrderByIdAsc(get(iban).getId());
    }

    // Lookups accept spaces and lower case, as people type IBANs.
    private static String compact(String iban) {
        return iban.replace(" ", "").toUpperCase(Locale.ROOT);
    }

    private static BankException accountNotFound(String iban) {
        return new BankException(ErrorCode.ACCOUNT_NOT_FOUND, "Account " + iban + " not found");
    }
}
