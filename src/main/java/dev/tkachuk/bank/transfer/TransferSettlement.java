package dev.tkachuk.bank.transfer;

import dev.tkachuk.bank.account.Account;
import dev.tkachuk.bank.account.AccountRepository;
import dev.tkachuk.bank.account.InternalAccounts;
import dev.tkachuk.bank.ledger.EntryType;
import dev.tkachuk.bank.ledger.LedgerEntry;
import dev.tkachuk.bank.ledger.LedgerEntryRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finishes a transfer to another bank: on ACSC the money moves from transit to settlement, on RJCT it goes back
 * to the customer. A repeated answer changes nothing.
 */
@Service
@RequiredArgsConstructor
public class TransferSettlement {

    public enum Outcome {
        APPLIED,
        ALREADY_APPLIED,
        CONFLICT,
        UNKNOWN_TRANSFER
    }

    private static final int DESCRIPTION_LENGTH = 140;
    private static final String RETURNED = "Returned: ";

    // The constructor takes the fields in this order; TransferSettlementTest calls it.
    private final TransferRepository transfers;
    private final AccountRepository accounts;
    private final LedgerEntryRepository ledgerEntries;
    private final InternalAccounts internalAccounts;
    private final Clock clock;

    @Transactional
    public Outcome settle(UUID transferId, String reportId) {
        Optional<Transfer> found = transfers.findByIdForUpdate(transferId);
        if (found.isEmpty()) {
            return Outcome.UNKNOWN_TRANSFER;
        }
        Transfer transfer = found.get();

        if (!transfer.isPending()) {
            return outcomeOfAnotherAnswer(transfer, TransferStatus.COMPLETED);
        }

        moveFromTransit(transfer, internalAccounts.settlementIban(), EntryType.SETTLEMENT, transfer.getDescription());

        transfer.settle(reportId);
        return Outcome.APPLIED;
    }

    @Transactional
    public Outcome reject(UUID transferId, String reportId) {
        Optional<Transfer> found = transfers.findByIdForUpdate(transferId);
        if (found.isEmpty()) {
            return Outcome.UNKNOWN_TRANSFER;
        }
        Transfer transfer = found.get();

        if (!transfer.isPending()) {
            return outcomeOfAnotherAnswer(transfer, TransferStatus.REJECTED);
        }

        moveFromTransit(transfer, transfer.getFromIban(), EntryType.REVERSAL, returned(transfer.getDescription()));

        transfer.reject(reportId);
        return Outcome.APPLIED;
    }

    // Shortened to fit a ledger entry, counting code points as PostgreSQL does.
    static String returned(String description) {
        String text = RETURNED + description;
        if (text.codePointCount(0, text.length()) <= DESCRIPTION_LENGTH) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, DESCRIPTION_LENGTH));
    }

    private static Outcome outcomeOfAnotherAnswer(Transfer transfer, TransferStatus statusThisAnswerGives) {
        return transfer.getStatus() == statusThisAnswerGives ? Outcome.ALREADY_APPLIED : Outcome.CONFLICT;
    }

    private void moveFromTransit(Transfer transfer, String toIban, EntryType type, String description) {
        LockedAccounts locked = LockedAccounts.lock(accounts, internalAccounts.transitIban(), toIban);
        Account transit = locked.from();
        Account to = locked.to();
        BigDecimal amount = transfer.getAmount();

        transit.debit(amount);
        to.credit(amount);

        Instant now = clock.instant();
        ledgerEntries.saveAll(List.of(
                LedgerEntry.debit(transit.getId(), amount, transit.getBalance(), type, transfer.getId(), description,
                        now),
                LedgerEntry.credit(to.getId(), amount, to.getBalance(), type, transfer.getId(), description, now)));
    }
}
