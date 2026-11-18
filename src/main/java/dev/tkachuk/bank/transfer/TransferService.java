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

/** Keep the seven fields in this order: TransferServiceTest builds the service through this constructor. */
@Service
@RequiredArgsConstructor
public class TransferService {

    /** The interbank payment system settles hryvnia only. */
    private static final String INTERBANK_CURRENCY = "UAH";

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerEntryRepository ledgerEntries;
    private final Clock clock;
    private final BankProperties bank;
    private final InternalAccounts internalAccounts;
    private final ApplicationEventPublisher events;

    /**
     * Moves money from a customer's account, exactly once per Idempotency-Key. Within the bank the money goes to
     * the payee's account and the transfer is COMPLETED. To another bank it goes to the transit account, the
     * transfer is PENDING, and an InterbankTransferPending event asks bank-integration to send it on; that bank's
     * answer finishes the transfer later (TransferSettlement). Everything happens in one transaction: either the
     * balances, the transfer, both ledger entries and the event are saved, or nothing is. Each step below says
     * why it comes where it does.
     */
    @Transactional
    public TransferResult transfer(TransferCommand command) {
        // 1. Validate without the database: no lock held, no round trip.
        TransferCommand request = normalised(command);
        boolean toAnotherBank = isToAnotherBank(request);

        // 2. Same account.
        if (request.fromIban().equals(request.toIban())) {
            throw new BankException(ErrorCode.SAME_ACCOUNT, "The two accounts of a transfer must differ");
        }

        // 3. Lock both accounts, the smaller IBAN first whatever the direction (LockedAccounts says why). A transfer
        // to another bank credits the transit account, where the money waits for that bank's answer.
        String creditedIban = toAnotherBank ? internalAccounts.transitIban() : request.toIban();
        LockedAccounts locked = LockedAccounts.lock(accounts, request.fromIban(), creditedIban);
        Account from = locked.from();
        Account to = locked.to();
        requireCustomer(from);
        if (!toAnotherBank) {
            requireCustomer(to);
        }

        // 4. Idempotency, after the locks: copies of one request that arrive together queue there, and each
        // finds the transfer the first one committed. A replay returns before the balance check: the money moved.
        Optional<Transfer> earlier = transfers.findByIdempotencyKey(request.idempotencyKey());
        if (earlier.isPresent()) {
            return replayOf(earlier.get(), request);
        }

        // 5. Currency.
        requireCurrency(from, request.currency());
        requireCurrency(to, request.currency());

        // 6. Move the money. The debit goes first: it is the step that can refuse, before anything has changed.
        from.debit(request.amount());
        to.credit(request.amount());

        // 7. Record it, with one moment for the transfer and both of its entries.
        Instant now = clock.instant();
        Transfer transfer = transfers.save(toAnotherBank
                ? Transfer.pending(request, now)
                : Transfer.completed(request, now));
        ledgerEntries.saveAll(List.of(
                LedgerEntry.debit(from.getId(), request.amount(), from.getBalance(), EntryType.TRANSFER,
                        transfer.getId(), request.description(), now),
                LedgerEntry.credit(to.getId(), request.amount(), to.getBalance(), EntryType.TRANSFER,
                        transfer.getId(), request.description(), now)));

        // 8. To another bank: ask bank-integration to send it on. The event is stored in this transaction and goes
        // to Kafka after the commit, so it exists exactly when the transfer does (the transactional outbox).
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

    /**
     * The command as the rest of the method uses it: compact upper-case IBANs, the amount with two decimals,
     * the payee's name and the description without surrounding spaces. A retry typed differently then compares
     * equal.
     */
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

    /** A name of spaces only counts as no name. */
    private static String nameOrNull(String name) {
        return name == null || name.isBlank() ? null : name.strip();
    }

    /**
     * False for an IBAN of this bank, true for one of another Ukrainian bank, which then needs the payee's name
     * (its payment message carries it) and hryvnia. An IBAN abroad is refused: paying it would take SWIFT, which
     * bank-integration does not speak.
     */
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

    /** The bank's own accounts answer as if they did not exist: customers cannot pay from or into them. */
    private static void requireCustomer(Account account) {
        if (!account.isCustomer()) {
            throw new BankException(ErrorCode.ACCOUNT_NOT_FOUND, "Account " + account.getIban() + " not found");
        }
    }

    /** The same request again changes nothing and returns the original; another request with its key is refused. */
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
