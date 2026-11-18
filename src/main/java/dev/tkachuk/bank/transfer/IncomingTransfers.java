package dev.tkachuk.bank.transfer;

import dev.tkachuk.bank.account.Account;
import dev.tkachuk.bank.account.AccountRepository;
import dev.tkachuk.bank.account.InternalAccounts;
import dev.tkachuk.bank.common.Amounts;
import dev.tkachuk.bank.common.iban.Iban;
import dev.tkachuk.bank.ledger.EntryType;
import dev.tkachuk.bank.ledger.LedgerEntry;
import dev.tkachuk.bank.ledger.LedgerEntryRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payments from customers of other banks to customers of this one (flow B), which bank-integration brings in. Each is
 * decided once, by its UETR: credited, its money moving from the settlement account to the payee, or refused, with no
 * money moving. Either way an IncomingTransferDecision event takes the decision back to bank-integration, which
 * answers the other bank with it.
 *
 * The payments arrive as Kafka events, delivered at least once, so the same payment can come twice: the second gets
 * the first one's decision again and changes nothing. Keep the six fields in this order: IncomingTransfersTest builds
 * the service through this constructor.
 */
@Service
@RequiredArgsConstructor
public class IncomingTransfers {

    /** What a payment did, and the decision on it, for the caller to log. */
    public record Received(Outcome outcome, IncomingTransferDecision decision) {
    }

    public enum Outcome {
        /** The payee has the money. */
        CREDITED,
        /** Refused: the payee's account does not exist here, or is kept in another currency. */
        REJECTED,
        /** A copy of a payment decided on already: its decision went out again, and nothing else changed. */
        ALREADY_DECIDED
    }

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerEntryRepository ledgerEntries;
    private final InternalAccounts internalAccounts;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    /**
     * Credits the payment, or refuses it, in one transaction with its transfer, its ledger entries and the decision
     * event. Each step below says why it comes where it does.
     */
    @Transactional
    public Received receive(IncomingTransferCommand command) {
        // 1. Validate without the database. The amount was checked by bank-integration already; a payee IBAN that
        // is not valid names no account here, which is a reason to refuse the payment, not an error.
        BigDecimal amount = Amounts.requireValid(command.amount());
        String payeeIban = Iban.tryParse(command.toIban()).map(Iban::value).orElse(command.toIban());

        // 2. Lock the settlement account, and the payee's with it, the smaller IBAN first like every movement of money
        // (LockedAccounts). Every payment in locks the settlement account, so two copies of one payment queue there.
        LockedAccounts.Found locked =
                LockedAccounts.lockIfPresent(accounts, internalAccounts.settlementIban(), payeeIban);
        Account settlement = locked.from().orElseThrow(() -> new IllegalStateException(
                "The settlement account " + internalAccounts.settlementIban() + " is gone"));
        Optional<Account> payee = locked.to().filter(Account::isCustomer);

        // 3. The UETR, after the locks: a copy finds the transfer the first one committed, and its decision goes out
        // again.
        Optional<Transfer> earlier = transfers.findByUetr(command.uetr());
        if (earlier.isPresent()) {
            return decided(Outcome.ALREADY_DECIDED, earlier.get());
        }

        // 4. The payee's account, read under its lock. A refusal moves no money and writes no ledger entry.
        IncomingTransferCommand payment = new IncomingTransferCommand(command.uetr(), command.fromIban(),
                command.fromName().strip(), payeeIban, command.toName().strip(), amount, command.currency(),
                command.description().strip(), command.receivedAt());
        Optional<IncomingTransferRejection> rejection = rejectionOf(payee, payment);
        Instant now = clock.instant();
        if (rejection.isPresent()) {
            return decided(Outcome.REJECTED, transfers.save(Transfer.incomingRejected(payment, rejection.get(), now)));
        }

        // 5. Move the money: out of the bank's position at the National Bank, which can go below zero, into the
        // payee's account.
        Account to = payee.orElseThrow();
        settlement.debit(amount);
        to.credit(amount);

        // 6. Record it, with one moment for the transfer and both of its entries.
        Transfer credited = transfers.save(Transfer.incomingCredited(payment, now));
        ledgerEntries.saveAll(List.of(
                LedgerEntry.debit(settlement.getId(), amount, settlement.getBalance(), EntryType.INCOMING,
                        credited.getId(), payment.description(), now),
                LedgerEntry.credit(to.getId(), amount, to.getBalance(), EntryType.INCOMING, credited.getId(),
                        payment.description(), now)));

        // 7. The decision for bank-integration.
        return decided(Outcome.CREDITED, credited);
    }

    /**
     * The decision for bank-integration, stored in this transaction and sent after the commit (the outbox), so it
     * exists exactly when the transfer does.
     */
    private Received decided(Outcome outcome, Transfer transfer) {
        IncomingTransferDecision decision = IncomingTransferDecision.of(transfer);
        events.publishEvent(decision);
        return new Received(outcome, decision);
    }

    /** No customer account with the payee's IBAN, or one in another currency, refuses the payment. */
    private static Optional<IncomingTransferRejection> rejectionOf(Optional<Account> payee,
            IncomingTransferCommand payment) {
        if (payee.isEmpty()) {
            return Optional.of(IncomingTransferRejection.UNKNOWN_ACCOUNT);
        }
        if (!payee.get().getCurrency().equals(payment.currency())) {
            return Optional.of(IncomingTransferRejection.CURRENCY_MISMATCH);
        }
        return Optional.empty();
    }
}
