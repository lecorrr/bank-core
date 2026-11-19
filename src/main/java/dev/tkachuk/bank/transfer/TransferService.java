package dev.tkachuk.bank.transfer;

import dev.tkachuk.bank.account.Account;
import dev.tkachuk.bank.account.AccountRepository;
import dev.tkachuk.bank.account.InternalAccounts;
import dev.tkachuk.bank.common.Amounts;
import dev.tkachuk.bank.common.BankProperties;
import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;
import dev.tkachuk.bank.common.iban.Iban;
import dev.tkachuk.bank.ledger.EntryType;
import dev.tkachuk.bank.ledger.LedgerEntry;
import dev.tkachuk.bank.ledger.LedgerEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Customer transfers, within the bank and to other banks, once per Idempotency-Key. */
@Service
@RequiredArgsConstructor
public class TransferService {

    private static final String INTERBANK_CURRENCY = "UAH";

    // The constructor takes the fields in this order; TransferServiceTest calls it.
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerEntryRepository ledgerEntries;
    private final Clock clock;
    private final BankProperties bank;
    private final InternalAccounts internalAccounts;
    private final ApplicationEventPublisher events;

    @Transactional
    public TransferResult transfer(TransferCommand command) {
        TransferCommand request = normalised(command);
        boolean toAnotherBank = isToAnotherBank(request);

        if (request.fromIban().equals(request.toIban())) {
            throw new BankException(ErrorCode.SAME_ACCOUNT, "The two accounts of a transfer must differ");
        }

        // To another bank the money waits in the transit account for that bank's answer.
        String creditedIban = toAnotherBank ? internalAccounts.transitIban() : request.toIban();
        LockedAccounts locked = LockedAccounts.lock(accounts, request.fromIban(), creditedIban);
        Account from = locked.from();
        Account to = locked.to();
        requireCustomer(from);
        if (!toAnotherBank) {
            requireCustomer(to);
        }

        // After the locks, so copies of a request that arrive together find the first one's transfer.
        Optional<Transfer> earlier = transfers.findByIdempotencyKey(request.idempotencyKey());
        if (earlier.isPresent()) {
            return replayOf(earlier.get(), request);
        }

        requireCurrency(from, request.currency());
        requireCurrency(to, request.currency());

        from.debit(request.amount());
        to.credit(request.amount());

        Instant now = clock.instant();
        Transfer transfer = transfers.save(toAnotherBank
                ? Transfer.pending(request, now)
                : Transfer.completed(request, now));
        ledgerEntries.saveAll(List.of(
                LedgerEntry.debit(from.getId(), request.amount(), from.getBalance(), EntryType.TRANSFER,
                        transfer.getId(), request.description(), now),
                LedgerEntry.credit(to.getId(), request.amount(), to.getBalance(), EntryType.TRANSFER,
                        transfer.getId(), request.description(), now)));

        // Stored with the transfer and sent to Kafka after the commit (transactional outbox).
        if (toAnotherBank) {
            events.publishEvent(InterbankTransferPending.of(transfer, from.getHolderName()));
        }

        return new TransferResult(transfer, false);
    }

    @Transactional(readOnly = true)
    public Transfer get(UUID transferId) {
        return transfers.findById(transferId).orElseThrow(() ->
                new BankException(ErrorCode.TRANSFER_NOT_FOUND, "Transfer " + transferId + " not found"));
    }

    private static TransferCommand normalised(TransferCommand command) {
        return new TransferCommand(
                command.idempotencyKey(),
                Iban.parse(command.fromIban()).value(),
                Iban.parse(command.toIban()).value(),
                nameOrNull(command.toName()),
                Amounts.requireValid(command.amount()),
                command.currency(),
                command.description().strip());
    }

    private static String nameOrNull(String name) {
        return name == null || name.isBlank() ? null : name.strip();
    }

    private boolean isToAnotherBank(TransferCommand request) {
        String bankCode = Iban.parse(request.toIban()).ukrainianBankCode().orElseThrow(() -> new BankException(
                ErrorCode.UNSUPPORTED_DESTINATION, "Transfers to banks outside Ukraine are not supported"));
        if (bankCode.equals(bank.code())) {
            return false;
        }
        if (request.toName() == null) {
            throw new BankException(ErrorCode.BENEFICIARY_NAME_REQUIRED,
                    "A transfer to another bank needs the payee's name (toName)");
        }
        if (!INTERBANK_CURRENCY.equals(request.currency())) {
            throw new BankException(ErrorCode.CURRENCY_MISMATCH,
                    "Transfers to other banks are in " + INTERBANK_CURRENCY + " only, not " + request.currency());
        }
        return true;
    }

    private static void requireCustomer(Account account) {
        if (!account.isCustomer()) {
            throw new BankException(ErrorCode.ACCOUNT_NOT_FOUND, "Account " + account.getIban() + " not found");
        }
    }

    private static TransferResult replayOf(Transfer original, TransferCommand request) {
        if (!original.hasSamePayloadAs(request)) {
            throw new BankException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key " + request.idempotencyKey() + " was already used for a different transfer");
        }
        return new TransferResult(original, true);
    }

    private static void requireCurrency(Account account, String currency) {
        if (!account.getCurrency().equals(currency)) {
            throw new BankException(ErrorCode.CURRENCY_MISMATCH,
                    "Account " + account.getIban() + " is in " + account.getCurrency() + ", not " + currency);
        }
    }
}
