package dev.tkachuk.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.tkachuk.bank.account.Account;
import dev.tkachuk.bank.account.AccountKind;
import dev.tkachuk.bank.account.AccountRepository;
import dev.tkachuk.bank.account.InternalAccounts;
import dev.tkachuk.bank.ledger.EntryDirection;
import dev.tkachuk.bank.ledger.EntryType;
import dev.tkachuk.bank.ledger.LedgerEntry;
import dev.tkachuk.bank.ledger.LedgerEntryRepository;
import dev.tkachuk.bank.support.TestAccounts;
import dev.tkachuk.bank.support.TestIbans;
import dev.tkachuk.bank.transfer.IncomingTransfers.Outcome;
import dev.tkachuk.bank.transfer.IncomingTransfers.Received;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A payment from a customer of another bank, with the repositories mocked: credited from the settlement account,
 * refused, or recognised as a copy. IncomingTransferIT runs the same through Kafka and PostgreSQL.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncomingTransfersTest {

    private static final Instant NOW = Instant.parse("2026-11-18T09:15:02Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-11-18T09:15:00Z");
    private static final UUID UETR = UUID.fromString("3b9f7a52-1c4d-4e8f-a0b6-7d2e5c9f1a34");

    @Mock
    private AccountRepository accounts;
    @Mock
    private TransferRepository transfers;
    @Mock
    private LedgerEntryRepository ledgerEntries;

    private final List<LedgerEntry> savedEntries = new ArrayList<>();
    private final List<Transfer> savedTransfers = new ArrayList<>();
    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher events = published::add;
    /** The locks taken and the lookups made, in order. */
    private final List<String> calls = new ArrayList<>();
    private IncomingTransfers incoming;
    private Account payee;
    private Account usd;
    private Account transit;
    private Account settlement;

    @BeforeEach
    void setUp() {
        incoming = new IncomingTransfers(accounts, transfers, ledgerEntries,
                new InternalAccounts(TestIbans.TRANSIT, TestIbans.SETTLEMENT), Clock.fixed(NOW, ZoneOffset.UTC),
                events);
        payee = TestAccounts.account(1, TestIbans.A, "UAH", "100.00");
        usd = TestAccounts.account(4, TestIbans.D_USD, "USD", "0.00");
        transit = TestAccounts.internal(5, TestIbans.TRANSIT, AccountKind.TRANSIT, "0.00");
        settlement = TestAccounts.internal(6, TestIbans.SETTLEMENT, AccountKind.SETTLEMENT, "0.00");
        for (Account account : List.of(payee, usd, transit, settlement)) {
            when(accounts.findByIbanForUpdate(account.getIban())).thenAnswer(call -> {
                calls.add("lock " + account.getIban());
                return Optional.of(account);
            });
        }
        // Saving gives a transfer its id, as the database would, and findByUetr finds what was saved.
        when(transfers.save(any(Transfer.class))).thenAnswer(call -> {
            Transfer transfer = call.getArgument(0);
            ReflectionTestUtils.setField(transfer, "id", UUID.randomUUID());
            savedTransfers.add(transfer);
            return transfer;
        });
        when(transfers.findByUetr(any(UUID.class))).thenAnswer(call -> {
            calls.add("find uetr");
            return savedTransfers.stream()
                    .filter(transfer -> transfer.getUetr().equals(call.getArgument(0)))
                    .findFirst();
        });
        when(ledgerEntries.saveAll(ArgumentMatchers.<LedgerEntry>anyIterable())).thenAnswer(call -> {
            List<LedgerEntry> saved = new ArrayList<>();
            call.<Iterable<LedgerEntry>>getArgument(0).forEach(saved::add);
            savedEntries.addAll(saved);
            return saved;
        });
    }

    @Test
    void aPaymentToACustomerMovesTheMoneyFromTheSettlementAccount() {
        Received received = incoming.receive(payment(TestIbans.A, "250.00", "UAH"));

        assertThat(received.outcome()).isEqualTo(Outcome.CREDITED);
        assertThat(payee.getBalance()).isEqualByComparingTo("350.00");
        assertThat(settlement.getBalance()).as("the bank's position at the National Bank").isEqualByComparingTo(
                "-250.00");
        Transfer transfer = savedTransfers.getFirst();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getUetr()).isEqualTo(UETR);
        assertThat(transfer.getIdempotencyKey()).isNull();
        assertThat(transfer.getFromIban()).isEqualTo(TestIbans.PARTNER);
        assertThat(transfer.getFromName()).isEqualTo("Olena Kovalenko");
        assertThat(transfer.getRejectionReason()).isNull();
        assertThat(transfer.getCreatedAt()).isEqualTo(NOW);

        assertThat(savedEntries).hasSize(2);
        LedgerEntry out = entryOf(settlement);
        assertThat(out.getDirection()).isEqualTo(EntryDirection.DEBIT);
        assertThat(out.getType()).isEqualTo(EntryType.INCOMING);
        assertThat(out.getBalanceAfter()).isEqualByComparingTo("-250.00");
        LedgerEntry in = entryOf(payee);
        assertThat(in.getDirection()).isEqualTo(EntryDirection.CREDIT);
        assertThat(in.getType()).isEqualTo(EntryType.INCOMING);
        assertThat(in.getAmount()).isEqualByComparingTo("250.00");
        assertThat(in.getBalanceAfter()).isEqualByComparingTo("350.00");
        assertThat(in.getTransferId()).isEqualTo(transfer.getId());
        assertThat(in.getDescription()).isEqualTo("For the concert tickets");
        assertThat(in.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void theDecisionGoesOutAsAnEventWithTheTransfer() {
        incoming.receive(payment(TestIbans.A, "250.00", "UAH"));

        Transfer transfer = savedTransfers.getFirst();
        assertThat(publishedDecisions()).containsExactly(new IncomingTransferDecision(UETR, transfer.getId(), null,
                NOW));
        assertThat(publishedDecisions().getFirst().credited()).isTrue();
    }

    @Test
    void aPaymentToAnAccountThatIsNotACustomersIsRefusedAndMovesNoMoney() {
        for (String notACustomer : new String[] {TestIbans.UNKNOWN, TestIbans.TRANSIT, TestIbans.SETTLEMENT,
                "UA00399999000000000000000000X"}) {
            savedTransfers.clear();

            Received received = incoming.receive(payment(notACustomer, "250.00", "UAH", UUID.randomUUID()));

            assertThat(received.outcome()).as(notACustomer).isEqualTo(Outcome.REJECTED);
            assertThat(received.decision().rejection()).isEqualTo(IncomingTransferRejection.UNKNOWN_ACCOUNT);
            assertThat(savedTransfers.getFirst().getStatus()).isEqualTo(TransferStatus.REJECTED);
            assertThat(savedTransfers.getFirst().getRejectionReason())
                    .isEqualTo(IncomingTransferRejection.UNKNOWN_ACCOUNT);
        }
        assertThat(savedEntries).isEmpty();
        assertThat(settlement.getBalance()).isEqualByComparingTo("0.00");
        assertThat(transit.getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void aPaymentInAnotherCurrencyThanThePayeesAccountIsRefused() {
        Received received = incoming.receive(payment(TestIbans.D_USD, "250.00", "UAH"));

        assertThat(received.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(received.decision().rejection()).isEqualTo(IncomingTransferRejection.CURRENCY_MISMATCH);
        assertThat(publishedDecisions().getFirst().credited()).isFalse();
        assertThat(usd.getBalance()).isEqualByComparingTo("0.00");
        assertThat(savedEntries).isEmpty();
    }

    @Test
    void aCopyOfAPaymentGetsTheSameDecisionAgainAndMovesNoMoreMoney() {
        incoming.receive(payment(TestIbans.A, "250.00", "UAH"));
        savedEntries.clear();

        Received again = incoming.receive(payment(TestIbans.A, "250.00", "UAH"));

        assertThat(again.outcome()).isEqualTo(Outcome.ALREADY_DECIDED);
        assertThat(savedTransfers).hasSize(1);
        assertThat(savedEntries).isEmpty();
        assertThat(payee.getBalance()).isEqualByComparingTo("350.00");
        List<IncomingTransferDecision> decisions = publishedDecisions();
        assertThat(decisions).hasSize(2);
        assertThat(decisions.get(1)).isEqualTo(decisions.get(0));
    }

    @Test
    void aCopyOfARefusedPaymentIsRefusedAgainEvenIfTheAccountExistsByNow() {
        incoming.receive(payment(TestIbans.UNKNOWN, "250.00", "UAH"));
        Account openedSince = TestAccounts.account(9, TestIbans.UNKNOWN, "UAH", "0.00");
        when(accounts.findByIbanForUpdate(TestIbans.UNKNOWN)).thenReturn(Optional.of(openedSince));

        Received again = incoming.receive(payment(TestIbans.UNKNOWN, "250.00", "UAH"));

        assertThat(again.outcome()).isEqualTo(Outcome.ALREADY_DECIDED);
        assertThat(again.decision().rejection()).isEqualTo(IncomingTransferRejection.UNKNOWN_ACCOUNT);
        assertThat(openedSince.getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void theSettlementAccountAndThePayeeAreLockedInIbanOrderBeforeTheUetrIsLookedUp() {
        incoming.receive(payment(TestIbans.A, "10.00", "UAH"));

        assertThat(TestIbans.SETTLEMENT.compareTo(TestIbans.A)).as("the test needs the settlement IBAN to sort first")
                .isNegative();
        // A copy that comes at the same time waits at the first lock, and then finds this payment's transfer.
        assertThat(calls).containsExactly("lock " + TestIbans.SETTLEMENT, "lock " + TestIbans.A, "find uetr");
        verify(accounts, times(2)).findByIbanForUpdate(any(String.class));
    }

    @Test
    void aPayeeIbanIsLookedUpAsAccountsAreKept() {
        Received received = incoming.receive(payment("ua84 3999 9900 0000 0000 0000 0000 1", "10.00", "UAH"));

        assertThat(received.outcome()).isEqualTo(Outcome.CREDITED);
        assertThat(savedTransfers.getFirst().getToIban()).isEqualTo(TestIbans.A);
    }

    @Test
    void namesAndTheDescriptionAreKeptWithoutSurroundingSpaces() {
        incoming.receive(new IncomingTransferCommand(UETR, TestIbans.PARTNER, " Olena Kovalenko ", TestIbans.A,
                " Artem Demo ", new BigDecimal("10"), "UAH", " For the concert tickets ", RECEIVED_AT));

        Transfer transfer = savedTransfers.getFirst();
        assertThat(transfer.getFromName()).isEqualTo("Olena Kovalenko");
        assertThat(transfer.getToName()).isEqualTo("Artem Demo");
        assertThat(transfer.getDescription()).isEqualTo("For the concert tickets");
        assertThat(transfer.getAmount().toPlainString()).isEqualTo("10.00");
    }

    @Test
    void nothingIsSavedOrPublishedWhenTheSettlementAccountIsMissing() {
        when(accounts.findByIbanForUpdate(TestIbans.SETTLEMENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> incoming.receive(payment(TestIbans.A, "10.00", "UAH")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TestIbans.SETTLEMENT);

        verify(transfers, never()).save(any());
        assertThat(published).isEmpty();
    }

    private static IncomingTransferCommand payment(String toIban, String amount, String currency) {
        return payment(toIban, amount, currency, UETR);
    }

    private static IncomingTransferCommand payment(String toIban, String amount, String currency, UUID uetr) {
        return new IncomingTransferCommand(uetr, TestIbans.PARTNER, "Olena Kovalenko", toIban, "Artem Demo",
                new BigDecimal(amount), currency, "For the concert tickets", RECEIVED_AT);
    }

    /** Every event published is a decision; the cast fails the test otherwise. */
    private List<IncomingTransferDecision> publishedDecisions() {
        return published.stream().map(IncomingTransferDecision.class::cast).toList();
    }

    private LedgerEntry entryOf(Account account) {
        return savedEntries.stream()
                .filter(entry -> entry.getAccountId().equals(account.getId()))
                .findFirst()
                .orElseThrow();
    }
}
