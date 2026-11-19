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

/** Payments from other banks: credited or rejected once per UETR, the decision sent back through the outbox. */
@Service
@RequiredArgsConstructor
public class IncomingTransfers {

    public record Received(Outcome outcome, IncomingTransferDecision decision) {
    }

    public enum Outcome {
        CREDITED,
        REJECTED,
        ALREADY_DECIDED
    }

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerEntryRepository ledgerEntries;
    private final InternalAccounts internalAccounts;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    @Transactional
    public Received receive(IncomingTransferCommand command) {
        BigDecimal amount = Amounts.requireValid(command.amount());
        String payeeIban = Iban.tryParse(command.toIban()).map(Iban::value).orElse(command.toIban());

        // Lock before looking up the UETR, so two copies of a payment queue on the settlement account.
        LockedAccounts.Found locked =
                LockedAccounts.lockIfPresent(accounts, internalAccounts.settlementIban(), payeeIban);
        Account settlement = locked.from().orElseThrow(() -> new IllegalStateException(
                "The settlement account " + internalAccounts.settlementIban() + " is gone"));
        Optional<Account> payee = locked.to().filter(Account::isCustomer);

        Optional<Transfer> earlier = transfers.findByUetr(command.uetr());
        if (earlier.isPresent()) {
            return decided(Outcome.ALREADY_DECIDED, earlier.get());
        }

        IncomingTransferCommand payment = new IncomingTransferCommand(command.uetr(), command.fromIban(),
                command.fromName().strip(), payeeIban, command.toName().strip(), amount, command.currency(),
                command.description().strip(), command.receivedAt());
        Optional<IncomingTransferRejection> rejection = rejectionOf(payee, payment);
        Instant now = clock.instant();
        if (rejection.isPresent()) {
            return decided(Outcome.REJECTED, transfers.save(Transfer.incomingRejected(payment, rejection.get(), now)));
        }

        Account to = payee.orElseThrow();
        settlement.debit(amount);
        to.credit(amount);

        Transfer credited = transfers.save(Transfer.incomingCredited(payment, now));
        ledgerEntries.saveAll(List.of(
                LedgerEntry.debit(settlement.getId(), amount, settlement.getBalance(), EntryType.INCOMING,
                        credited.getId(), payment.description(), now),
                LedgerEntry.credit(to.getId(), amount, to.getBalance(), EntryType.INCOMING, credited.getId(),
                        payment.description(), now)));

        return decided(Outcome.CREDITED, credited);
    }

    private Received decided(Outcome outcome, Transfer transfer) {
        IncomingTransferDecision decision = IncomingTransferDecision.of(transfer);
        events.publishEvent(decision);
        return new Received(outcome, decision);
    }

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
