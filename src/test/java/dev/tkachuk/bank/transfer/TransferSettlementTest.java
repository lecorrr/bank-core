package dev.tkachuk.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;
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
import dev.tkachuk.bank.transfer.TransferSettlement.Outcome;
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
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferSettlementTest {

    private static final Instant NOW = Instant.parse("2026-11-17T20:17:09Z");

    @Mock
    private TransferRepository transfers;
    @Mock
    private AccountRepository accounts;
    @Mock
    private LedgerEntryRepository ledgerEntries;

    private final List<LedgerEntry> savedEntries = new ArrayList<>();
    private TransferSettlement settlement;
    private Account payer;
    private Account transit;
    private Account settlementAccount;
    private Transfer pending;

    @BeforeEach
    void setUp() {
        settlement = new TransferSettlement(transfers, accounts, ledgerEntries,
                new InternalAccounts(TestIbans.TRANSIT, TestIbans.SETTLEMENT), Clock.fixed(NOW, ZoneOffset.UTC));
        payer = TestAccounts.account(1, TestIbans.A, "UAH", "850.00");
        transit = TestAccounts.internal(5, TestIbans.TRANSIT, AccountKind.TRANSIT, "150.00");
        settlementAccount = TestAccounts.internal(6, TestIbans.SETTLEMENT, AccountKind.SETTLEMENT, "0.00");
        for (Account account : List.of(payer, transit, settlementAccount)) {
            when(accounts.findByIbanForUpdate(account.getIban())).thenReturn(Optional.of(account));
        }

        pending = Transfer.pending(new TransferCommand("key-1", TestIbans.A, TestIbans.PARTNER, "Olena Kovalenko",
                new BigDecimal("150.00"), "UAH", "Rent"), NOW.minusSeconds(60));
        ReflectionTestUtils.setField(pending, "id", UUID.randomUUID());
        when(transfers.findByIdForUpdate(pending.getId())).thenReturn(Optional.of(pending));

        when(ledgerEntries.saveAll(ArgumentMatchers.<LedgerEntry>anyIterable())).thenAnswer(call -> {
            List<LedgerEntry> saved = new ArrayList<>();
            call.<Iterable<LedgerEntry>>getArgument(0).forEach(saved::add);
            savedEntries.addAll(saved);
            return saved;
        });
    }

    @Test
    void shouldMoveAcceptedTransferToSettlementAccount() {
        assertThat(settlement.settle(pending.getId(), "REPORT-1")).isEqualTo(Outcome.APPLIED);

        assertThat(pending.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(pending.getStatusReportId()).isEqualTo("REPORT-1");
        assertThat(transit.getBalance()).isEqualByComparingTo("0.00");
        assertThat(settlementAccount.getBalance()).isEqualByComparingTo("150.00");
        assertThat(payer.getBalance()).as("the payer paid when the transfer was made").isEqualByComparingTo("850.00");

        assertThat(savedEntries).hasSize(2);
        LedgerEntry out = entryOf(transit);
        assertThat(out.getDirection()).isEqualTo(EntryDirection.DEBIT);
        assertThat(out.getType()).isEqualTo(EntryType.SETTLEMENT);
        assertThat(out.getAmount()).isEqualByComparingTo("150.00");
        assertThat(out.getBalanceAfter()).isEqualByComparingTo("0.00");
        assertThat(out.getTransferId()).isEqualTo(pending.getId());
        assertThat(out.getDescription()).isEqualTo("Rent");
        assertThat(out.getCreatedAt()).isEqualTo(NOW);
        LedgerEntry in = entryOf(settlementAccount);
        assertThat(in.getDirection()).isEqualTo(EntryDirection.CREDIT);
        assertThat(in.getType()).isEqualTo(EntryType.SETTLEMENT);
        assertThat(in.getBalanceAfter()).isEqualByComparingTo("150.00");
        assertThat(in.getTransferId()).isEqualTo(pending.getId());
    }

    @Test
    void shouldReturnMoneyForRejectedTransfer() {
        assertThat(settlement.reject(pending.getId(), "REPORT-1")).isEqualTo(Outcome.APPLIED);

        assertThat(pending.getStatus()).isEqualTo(TransferStatus.REJECTED);
        assertThat(pending.getStatusReportId()).isEqualTo("REPORT-1");
        assertThat(transit.getBalance()).isEqualByComparingTo("0.00");
        assertThat(payer.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(settlementAccount.getBalance()).isEqualByComparingTo("0.00");

        assertThat(savedEntries).hasSize(2);
        LedgerEntry out = entryOf(transit);
        assertThat(out.getDirection()).isEqualTo(EntryDirection.DEBIT);
        assertThat(out.getType()).isEqualTo(EntryType.REVERSAL);
        LedgerEntry back = entryOf(payer);
        assertThat(back.getDirection()).isEqualTo(EntryDirection.CREDIT);
        assertThat(back.getType()).isEqualTo(EntryType.REVERSAL);
        assertThat(back.getAmount()).isEqualByComparingTo("150.00");
        assertThat(back.getBalanceAfter()).isEqualByComparingTo("1000.00");
        assertThat(back.getTransferId()).isEqualTo(pending.getId());
        assertThat(back.getDescription()).isEqualTo("Returned: Rent");
    }

    @Test
    void shouldIgnoreRepeatedAcceptance() {
        settlement.settle(pending.getId(), "REPORT-1");
        savedEntries.clear();

        assertThat(settlement.settle(pending.getId(), "REPORT-1")).isEqualTo(Outcome.ALREADY_APPLIED);

        assertThat(savedEntries).isEmpty();
        assertThat(settlementAccount.getBalance()).isEqualByComparingTo("150.00");
        assertThat(transit.getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void shouldIgnoreRepeatedRejection() {
        settlement.reject(pending.getId(), "REPORT-1");
        savedEntries.clear();

        assertThat(settlement.reject(pending.getId(), "REPORT-1")).isEqualTo(Outcome.ALREADY_APPLIED);

        assertThat(savedEntries).isEmpty();
        assertThat(payer.getBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void shouldReportConflictForOppositeAnswer() {
        settlement.settle(pending.getId(), "REPORT-1");
        savedEntries.clear();

        assertThat(settlement.reject(pending.getId(), "REPORT-2")).isEqualTo(Outcome.CONFLICT);

        assertThat(pending.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(pending.getStatusReportId()).isEqualTo("REPORT-1");
        assertThat(savedEntries).isEmpty();
        assertThat(payer.getBalance()).isEqualByComparingTo("850.00");
        assertThat(settlementAccount.getBalance()).isEqualByComparingTo("150.00");
    }

    @Test
    void shouldReportConflictForInternalTransfer() {
        Transfer withinTheBank = Transfer.completed(new TransferCommand("key-2", TestIbans.A, TestIbans.B,
                new BigDecimal("10.00"), "UAH", "Lunch"), NOW.minusSeconds(60));
        ReflectionTestUtils.setField(withinTheBank, "id", UUID.randomUUID());
        when(transfers.findByIdForUpdate(withinTheBank.getId())).thenReturn(Optional.of(withinTheBank));

        assertThat(settlement.reject(withinTheBank.getId(), "REPORT-1")).isEqualTo(Outcome.CONFLICT);

        assertThat(withinTheBank.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(savedEntries).isEmpty();
    }

    @Test
    void shouldIgnoreAnswerForUnknownTransfer() {
        assertThat(settlement.settle(UUID.randomUUID(), "REPORT-1")).isEqualTo(Outcome.UNKNOWN_TRANSFER);
        assertThat(settlement.reject(UUID.randomUUID(), "REPORT-1")).isEqualTo(Outcome.UNKNOWN_TRANSFER);

        verify(accounts, never()).findByIbanForUpdate(any());
        assertThat(savedEntries).isEmpty();
    }

    @Test
    void shouldLockAccountsInIbanOrder() {
        settlement.settle(pending.getId(), "REPORT-1");

        ArgumentCaptor<String> locked = ArgumentCaptor.forClass(String.class);
        verify(accounts, times(2)).findByIbanForUpdate(locked.capture());
        assertThat(TestIbans.SETTLEMENT.compareTo(TestIbans.TRANSIT)).as("the test needs SETTLEMENT to sort first")
                .isNegative();
        assertThat(locked.getAllValues()).containsExactly(TestIbans.SETTLEMENT, TestIbans.TRANSIT);
    }

    @Test
    void shouldShortenReturnedDescriptionToFit() {
        String longest = "x".repeat(140);
        String emojiAtTheCut = "y".repeat(129) + "\uD83D\uDE00" + "z";

        assertThat(TransferSettlement.returned("Rent")).isEqualTo("Returned: Rent");
        assertThat(TransferSettlement.returned(longest)).startsWith("Returned: ").hasSize(140);
        String cut = TransferSettlement.returned(emojiAtTheCut);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(140);
        assertThat(cut).endsWith("\uD83D\uDE00");
    }

    private LedgerEntry entryOf(Account account) {
        List<LedgerEntry> entries = savedEntries.stream()
                .filter(entry -> account.getId().equals(entry.getAccountId()))
                .toList();
        assertThat(entries).as("ledger entries of " + account.getIban()).hasSize(1);
        return entries.get(0);
    }
}
