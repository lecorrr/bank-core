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
 * Finishes a transfer to another bank once that bank has answered. Accepted: the money moves on from the transit
 * account to the settlement account, and the transfer is COMPLETED. Refused: it goes back from the transit account
 * to the customer, and the transfer is REJECTED. Either way two new ledger entries record the move; nothing is
 * changed or deleted, so the customer's statement shows the money leaving and, after a refusal, coming back.
 *
 * The answers arrive as Kafka events, which are delivered at least once, so the same answer can come twice: the
 * second changes nothing. Keep the five fields in this order: TransferSettlementTest uses this constructor.
 */
@Service
@RequiredArgsConstructor
public class TransferSettlement {

    /** What an answer did, for the caller to log. */
    public enum Outcome {
        /** The money moved and the transfer has its final status. */
        APPLIED,
        /** The transfer already had this answer: a repeated event. */
        ALREADY_APPLIED,
        /** The transfer already had the opposite answer. Nothing changes; a person has to look at it. */
        CONFLICT,
        /** No transfer has this id. */
        UNKNOWN_TRANSFER
    }

    /** The length of a ledger entry's description. */
    private static final int DESCRIPTION_LENGTH = 140;
    private static final String RETURNED = "Returned: ";

    private final TransferRepository transfers;
    private final AccountRepository accounts;
    private final LedgerEntryRepository ledgerEntries;
    private final InternalAccounts internalAccounts;
    private final Clock clock;

    /**
     * The other bank accepted the transfer: its money moves from the transit account to the settlement account,
     * and the transfer is COMPLETED.
     *
     * @param reportId the MsgId of that bank's status report (pacs.002), kept with the transfer
     */
    @Transactional
    public Outcome settle(UUID transferId, String reportId) {
        // 1. Lock the transfer, so that two answers about it are handled one after the other.
        Optional<Transfer> found = transfers.findByIdForUpdate(transferId);
        if (found.isEmpty()) {
            return Outcome.UNKNOWN_TRANSFER;
        }
        Transfer transfer = found.get();

        // 2. Only a PENDING transfer takes an answer. The same answer again is a repeated event.
        if (!transfer.isPending()) {
            return outcomeOfAnotherAnswer(transfer, TransferStatus.COMPLETED);
        }

        // 3. Move the money on to the settlement account, with both ledger entries.
        moveFromTransit(transfer, internalAccounts.settlementIban(), EntryType.SETTLEMENT, transfer.getDescription());

        // 4. The final status, with the report that decided it.
        transfer.settle(reportId);
        return Outcome.APPLIED;
    }

    /**
     * The other bank refused the transfer: its money goes back from the transit account to the customer, and the
     * transfer is REJECTED.
     *
     * @param reportId the MsgId of that bank's status report (pacs.002), kept with the transfer
     */
    @Transactional
    public Outcome reject(UUID transferId, String reportId) {
        // 1. Lock the transfer, so that two answers about it are handled one after the other.
        Optional<Transfer> found = transfers.findByIdForUpdate(transferId);
        if (found.isEmpty()) {
            return Outcome.UNKNOWN_TRANSFER;
        }
        Transfer transfer = found.get();

        // 2. Only a PENDING transfer takes an answer. The same answer again is a repeated event.
        if (!transfer.isPending()) {
            return outcomeOfAnotherAnswer(transfer, TransferStatus.REJECTED);
        }

        // 3. Give the money back to the customer, with both ledger entries.
        moveFromTransit(transfer, transfer.getFromIban(), EntryType.REVERSAL, returned(transfer.getDescription()));

        // 4. The final status, with the report that decided it.
        transfer.reject(reportId);
        return Outcome.APPLIED;
    }

    /**
     * "Returned: Rent", the description of the entries that give a refused transfer's money back. Shortened to fit
     * a ledger entry when the original description was already long.
     */
    static String returned(String description) {
        String text = RETURNED + description;
        // Counted in code points, as PostgreSQL counts characters, so an emoji is never cut in half.
        if (text.codePointCount(0, text.length()) <= DESCRIPTION_LENGTH) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, DESCRIPTION_LENGTH));
    }

    /** A transfer that is no longer PENDING already had its answer: the same one again, or the opposite one. */
    private static Outcome outcomeOfAnotherAnswer(Transfer transfer, TransferStatus statusThisAnswerGives) {
        return transfer.getStatus() == statusThisAnswerGives ? Outcome.ALREADY_APPLIED : Outcome.CONFLICT;
    }

    /**
     * Takes the transfer's amount out of the transit account and puts it into the other account, both locked in IBAN
     * order like every movement of money, and records both sides with one moment.
     */
    private void moveFromTransit(Transfer transfer, String toIban, EntryType type, String description) {
        LockedAccounts locked = LockedAccounts.lock(accounts, internalAccounts.transitIban(), toIban);
        Account transit = locked.from();
        Account to = locked.to();
        BigDecimal amount = transfer.getAmount();

        // The transit account holds the money of every PENDING transfer, so this debit cannot refuse unless the
        // ledger is already wrong; if it does, the answer fails and is tried again, which keeps the problem visible.
        transit.debit(amount);
        to.credit(amount);

        Instant now = clock.instant();
        ledgerEntries.saveAll(List.of(
                LedgerEntry.debit(transit.getId(), amount, transit.getBalance(), type, transfer.getId(), description,
                        now),
                LedgerEntry.credit(to.getId(), amount, to.getBalance(), type, transfer.getId(), description, now)));
    }
}
