package dev.tkachuk.bank.transfer;

import static dev.tkachuk.bank.support.BankErrors.codeOf;
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
import dev.tkachuk.bank.common.BankProperties;
import dev.tkachuk.bank.common.error.ErrorCode;
import dev.tkachuk.bank.ledger.EntryDirection;
import dev.tkachuk.bank.ledger.EntryType;
import dev.tkachuk.bank.ledger.LedgerEntry;
import dev.tkachuk.bank.ledger.LedgerEntryRepository;
import dev.tkachuk.bank.support.TestAccounts;
import dev.tkachuk.bank.support.TestIbans;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The transfer rules, with the repositories and the event publisher mocked: fast, and every case is easy to set
 * up. TransferConcurrencyIT checks the locking against a real PostgreSQL, InterbankTransferIT the events.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

    @Mock
    private AccountRepository accounts;
    @Mock
    private TransferRepository transfers;
    @Mock
    private LedgerEntryRepository ledgerEntries;
    @Mock
    private ApplicationEventPublisher events;

    private final List<LedgerEntry> savedEntries = new ArrayList<>();
    private TransferService service;
    private Account a;
    private Account b;
    private Account usd;
    private Account transit;
    private Account settlement;

    @BeforeEach
    void setUp() {
        service = new TransferService(accounts, transfers, ledgerEntries, Clock.fixed(NOW, ZoneOffset.UTC),
                new BankProperties("399999"), new InternalAccounts(TestIbans.TRANSIT, TestIbans.SETTLEMENT), events);
        a = TestAccounts.account(1, TestIbans.A, "UAH", "1000.00");
        b = TestAccounts.account(2, TestIbans.B, "UAH", "0.00");
        usd = TestAccounts.account(4, TestIbans.D_USD, "USD", "1000.00");
        transit = TestAccounts.internal(5, TestIbans.TRANSIT, AccountKind.TRANSIT, "0.00");
        settlement = TestAccounts.internal(6, TestIbans.SETTLEMENT, AccountKind.SETTLEMENT, "0.00");
        for (Account account : List.of(a, b, usd, transit, settlement)) {
            when(accounts.findByIbanForUpdate(account.getIban())).thenReturn(Optional.of(account));
        }
        // Saving gives a transfer its id, as the database would. Either save style is fine.
        when(transfers.save(any(Transfer.class))).thenAnswer(call -> withId(call.getArgument(0)));
        when(transfers.saveAndFlush(any(Transfer.class))).thenAnswer(call -> withId(call.getArgument(0)));
        when(ledgerEntries.save(any(LedgerEntry.class))).thenAnswer(call -> {
            LedgerEntry entry = call.getArgument(0);
            savedEntries.add(entry);
            return entry;
        });
        when(ledgerEntries.saveAll(ArgumentMatchers.<LedgerEntry>anyIterable())).thenAnswer(call -> {
            List<LedgerEntry> saved = new ArrayList<>();
            call.<Iterable<LedgerEntry>>getArgument(0).forEach(saved::add);
            savedEntries.addAll(saved);
            return saved;
        });
    }

    @Test
    void movesTheMoneyAndRecordsBothSides() {
        TransferResult result = service.transfer(command("key-1", TestIbans.A, TestIbans.B, "150.00", "Rent"));

        assertThat(result.replayed()).isFalse();
        Transfer transfer = result.transfer();
        assertThat(transfer.getId()).isNotNull();
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.getAmount()).isEqualByComparingTo("150.00");
        assertThat(transfer.getCreatedAt()).isEqualTo(NOW);
        assertThat(a.getBalance()).isEqualByComparingTo("850.00");
        assertThat(b.getBalance()).isEqualByComparingTo("150.00");

        assertThat(savedEntries).hasSize(2);
        LedgerEntry debit = entryOf(a);
        assertThat(debit.getDirection()).isEqualTo(EntryDirection.DEBIT);
        assertThat(debit.getType()).isEqualTo(EntryType.TRANSFER);
        assertThat(debit.getAmount()).isEqualByComparingTo("150.00");
        assertThat(debit.getBalanceAfter()).isEqualByComparingTo("850.00");
        assertThat(debit.getTransferId()).isEqualTo(transfer.getId());
        assertThat(debit.getDescription()).isEqualTo("Rent");
        assertThat(debit.getCreatedAt()).isEqualTo(NOW);
        LedgerEntry credit = entryOf(b);
        assertThat(credit.getDirection()).isEqualTo(EntryDirection.CREDIT);
        assertThat(credit.getType()).isEqualTo(EntryType.TRANSFER);
        assertThat(credit.getAmount()).isEqualByComparingTo("150.00");
        assertThat(credit.getBalanceAfter()).isEqualByComparingTo("150.00");
        assertThat(credit.getTransferId()).isEqualTo(transfer.getId());
    }

    @Test
    void storesTheRequestNormalised() {
        String typedFrom = "ua84 3999 9900 0000 0000 0000 0000 1";
        String typedTo = TestIbans.B.toLowerCase(Locale.ROOT);

        Transfer transfer = service.transfer(command("key-1", typedFrom, typedTo, "150", "  Rent  ")).transfer();

        assertThat(transfer.getFromIban()).isEqualTo(TestIbans.A);
        assertThat(transfer.getToIban()).isEqualTo(TestIbans.B);
        assertThat(transfer.getAmount().toPlainString()).isEqualTo("150.00");
        assertThat(transfer.getDescription()).isEqualTo("Rent");
    }

    @Test
    void locksBothAccountsInTheSameOrderWhateverTheDirection() {
        service.transfer(command("key-1", TestIbans.A, TestIbans.B, "10.00", "There"));
        service.transfer(command("key-2", TestIbans.B, TestIbans.A, "10.00", "And back"));

        ArgumentCaptor<String> locked = ArgumentCaptor.forClass(String.class);
        verify(accounts, times(4)).findByIbanForUpdate(locked.capture());
        List<String> order = locked.getAllValues();
        assertThat(order.subList(0, 2)).as("accounts locked for A to B").containsExactlyInAnyOrder(TestIbans.A,
                TestIbans.B);
        assertThat(order.subList(2, 4)).as("B to A must lock in the same order, or two transfers can deadlock")
                .isEqualTo(order.subList(0, 2));
    }

    @Test
    void refusesWhenTheMoneyIsNotThere() {
        assertThat(codeOf(() -> service.transfer(command("key-1", TestIbans.A, TestIbans.B, "1000.01", "Too much"))))
                .isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);

        assertNothingChanged();
    }

    @Test
    void refusesATransferToTheSameAccount() {
        String sameAccountTypedDifferently = TestIbans.A.toLowerCase(Locale.ROOT);

        assertThat(codeOf(() -> service.transfer(
                command("key-1", TestIbans.A, sameAccountTypedDifferently, "10.00", "To myself"))))
                .isEqualTo(ErrorCode.SAME_ACCOUNT);

        assertNothingChanged();
    }

    @Test
    void refusesWhenTheCurrenciesDoNotMatch() {
        assertThat(codeOf(() -> service.transfer(command("key-1", TestIbans.A, TestIbans.D_USD, "10.00", "To USD"))))
                .as("UAH account to USD account")
                .isEqualTo(ErrorCode.CURRENCY_MISMATCH);
        assertThat(codeOf(() -> service.transfer(new TransferCommand("key-2", TestIbans.A, TestIbans.B,
                new BigDecimal("10.00"), "USD", "Wrong currency"))))
                .as("USD requested between two UAH accounts")
                .isEqualTo(ErrorCode.CURRENCY_MISMATCH);

        assertNothingChanged();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-1", "10.555"})
    void refusesAmountsThatAreNotMoney(String amount) {
        assertThat(codeOf(() -> service.transfer(command("key-1", TestIbans.A, TestIbans.B, amount, "Odd amount"))))
                .isEqualTo(ErrorCode.INVALID_AMOUNT);

        assertNothingChanged();
    }

    @Test
    void refusesInvalidIbansBeforeTouchingTheDatabase() {
        String wrongCheckDigits = "UA853999990000000000000000001";

        assertThat(codeOf(() -> service.transfer(command("key-1", wrongCheckDigits, TestIbans.B, "1.00", "From"))))
                .isEqualTo(ErrorCode.INVALID_IBAN);
        assertThat(codeOf(() -> service.transfer(command("key-2", TestIbans.A, wrongCheckDigits, "1.00", "To"))))
                .isEqualTo(ErrorCode.INVALID_IBAN);

        verify(accounts, never()).findByIbanForUpdate(any());
        assertNothingChanged();
    }

    @Test
    void refusesUnknownAccounts() {
        assertThat(codeOf(() -> service.transfer(command("key-1", TestIbans.A, TestIbans.UNKNOWN, "1.00", "To"))))
                .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
        assertThat(codeOf(() -> service.transfer(command("key-2", TestIbans.UNKNOWN, TestIbans.A, "1.00", "From"))))
                .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);

        assertNothingChanged();
    }

    @Test
    void repeatedRequestReturnsTheOriginalWithoutMovingMoneyAgain() {
        Transfer original = existingTransfer("key-1", "150.00", "Rent");
        // The money already moved, so a replay must not look at the balance again.
        ReflectionTestUtils.setField(a, "balance", new BigDecimal("0.00"));

        TransferResult result = service.transfer(command("key-1", TestIbans.A, TestIbans.B, "150.00", "Rent"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.transfer()).isSameAs(original);
        assertThat(a.getBalance()).isEqualByComparingTo("0.00");
        assertThat(b.getBalance()).isEqualByComparingTo("0.00");
        verify(transfers, never()).save(any());
        assertThat(savedEntries).isEmpty();
    }

    @Test
    void replayComparesTheNormalisedRequest() {
        Transfer original = existingTransfer("key-1", "150.00", "Rent");

        String typedFrom = TestIbans.A.toLowerCase(Locale.ROOT);

        TransferResult result = service.transfer(command("key-1", typedFrom, TestIbans.B, "150", " Rent "));

        assertThat(result.replayed()).isTrue();
        assertThat(result.transfer()).isSameAs(original);
    }

    @Test
    void refusesTheSameKeyForADifferentRequest() {
        existingTransfer("key-1", "150.00", "Rent");

        assertThat(codeOf(() -> service.transfer(command("key-1", TestIbans.A, TestIbans.B, "200.00", "Rent"))))
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);

        assertNothingChanged();
    }

    @Test
    void aTransferWithinTheBankPublishesNothing() {
        service.transfer(command("key-1", TestIbans.A, TestIbans.B, "150.00", "Rent"));

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aTransferToAnotherBankWaitsInTheTransitAccount() {
        Transfer transfer = service.transfer(toPartner("key-1", "150.00")).transfer();

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.getToIban()).isEqualTo(TestIbans.PARTNER);
        assertThat(transfer.getToName()).isEqualTo("Olena Kovalenko");
        assertThat(a.getBalance()).isEqualByComparingTo("850.00");
        assertThat(transit.getBalance()).isEqualByComparingTo("150.00");

        assertThat(savedEntries).hasSize(2);
        LedgerEntry debit = entryOf(a);
        assertThat(debit.getDirection()).isEqualTo(EntryDirection.DEBIT);
        assertThat(debit.getType()).isEqualTo(EntryType.TRANSFER);
        assertThat(debit.getTransferId()).isEqualTo(transfer.getId());
        LedgerEntry credit = entryOf(transit);
        assertThat(credit.getDirection()).isEqualTo(EntryDirection.CREDIT);
        assertThat(credit.getType()).isEqualTo(EntryType.TRANSFER);
        assertThat(credit.getBalanceAfter()).isEqualByComparingTo("150.00");
        assertThat(credit.getTransferId()).isEqualTo(transfer.getId());
    }

    @Test
    void aTransferToAnotherBankAsksForItToBeSent() {
        Transfer transfer = service.transfer(toPartner("key-1", "150.00")).transfer();

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new InterbankTransferPending(transfer.getId(), TestIbans.A,
                "Holder of " + TestIbans.A, TestIbans.PARTNER, "Olena Kovalenko", new BigDecimal("150.00"), "UAH",
                "Rent", NOW));
    }

    @Test
    void theTransitAccountIsLockedInIbanOrderLikeAnyOther() {
        service.transfer(toPartner("key-1", "10.00"));

        ArgumentCaptor<String> locked = ArgumentCaptor.forClass(String.class);
        verify(accounts, times(2)).findByIbanForUpdate(locked.capture());
        assertThat(TestIbans.TRANSIT.compareTo(TestIbans.A)).as("the test needs the transit IBAN to sort first")
                .isNegative();
        assertThat(locked.getAllValues()).containsExactly(TestIbans.TRANSIT, TestIbans.A);
    }

    @Test
    void anotherBankNeedsThePayeesName() {
        for (String missingName : new String[] {null, "   "}) {
            assertThat(codeOf(() -> service.transfer(new TransferCommand("key-1", TestIbans.A, TestIbans.PARTNER,
                    missingName, new BigDecimal("10.00"), "UAH", "Rent"))))
                    .isEqualTo(ErrorCode.BENEFICIARY_NAME_REQUIRED);
        }

        verify(accounts, never()).findByIbanForUpdate(any());
        assertNothingChanged();
    }

    @Test
    void anotherBankIsPaidInHryvniaOnly() {
        assertThat(codeOf(() -> service.transfer(new TransferCommand("key-1", TestIbans.D_USD, TestIbans.PARTNER,
                "Olena Kovalenko", new BigDecimal("10.00"), "USD", "In dollars"))))
                .isEqualTo(ErrorCode.CURRENCY_MISMATCH);

        // The transit account would refuse dollars too, but only after rows were locked for a request that cannot work.
        verify(accounts, never()).findByIbanForUpdate(any());
        assertNothingChanged();
    }

    @Test
    void aUahTransferToAnotherBankFromADollarAccountIsRefused() {
        assertThat(codeOf(() -> service.transfer(new TransferCommand("key-1", TestIbans.D_USD, TestIbans.PARTNER,
                "Olena Kovalenko", new BigDecimal("10.00"), "UAH", "Wrong account"))))
                .isEqualTo(ErrorCode.CURRENCY_MISMATCH);

        assertNothingChanged();
    }

    @Test
    void banksAbroadAreRefusedBeforeTouchingTheDatabase() {
        assertThat(codeOf(() -> service.transfer(new TransferCommand("key-1", TestIbans.A, TestIbans.GERMAN,
                "Hans Becker", new BigDecimal("10.00"), "UAH", "Abroad"))))
                .isEqualTo(ErrorCode.UNSUPPORTED_DESTINATION);

        verify(accounts, never()).findByIbanForUpdate(any());
        assertNothingChanged();
    }

    @Test
    void customersCannotPayIntoOrOutOfTheBanksOwnAccounts() {
        assertThat(codeOf(() -> service.transfer(command("key-1", TestIbans.A, TestIbans.TRANSIT, "10.00", "In"))))
                .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
        assertThat(codeOf(() -> service.transfer(command("key-2", TestIbans.SETTLEMENT, TestIbans.A, "10.00",
                "Out"))))
                .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);

        assertNothingChanged();
    }

    @Test
    void aRepeatedTransferToAnotherBankIsNotSentAgain() {
        Transfer original = withId(Transfer.pending(new TransferCommand("key-1", TestIbans.A, TestIbans.PARTNER,
                "Olena Kovalenko", new BigDecimal("150.00"), "UAH", "Rent"), NOW.minusSeconds(60)));
        when(transfers.findByIdempotencyKey("key-1")).thenReturn(Optional.of(original));

        TransferResult result = service.transfer(new TransferCommand("key-1", TestIbans.A, TestIbans.PARTNER,
                " Olena Kovalenko ", new BigDecimal("150"), "UAH", "Rent"));

        assertThat(result.replayed()).isTrue();
        assertThat(result.transfer()).isSameAs(original);
        assertNothingChanged();
    }

    private Transfer existingTransfer(String idempotencyKey, String amount, String description) {
        Transfer transfer = withId(Transfer.completed(new TransferCommand(idempotencyKey, TestIbans.A, TestIbans.B,
                new BigDecimal(amount), "UAH", description), NOW.minusSeconds(60)));
        when(transfers.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(transfer));
        return transfer;
    }

    private void assertNothingChanged() {
        assertThat(a.getBalance()).as("balance of A").isEqualByComparingTo("1000.00");
        assertThat(b.getBalance()).as("balance of B").isEqualByComparingTo("0.00");
        assertThat(usd.getBalance()).as("balance of the USD account").isEqualByComparingTo("1000.00");
        assertThat(transit.getBalance()).as("balance of the transit account").isEqualByComparingTo("0.00");
        verify(transfers, never()).save(any());
        verify(transfers, never()).saveAndFlush(any());
        assertThat(savedEntries).as("ledger entries").isEmpty();
        verify(events, never()).publishEvent(any(Object.class));
    }

    private LedgerEntry entryOf(Account account) {
        List<LedgerEntry> entries = savedEntries.stream()
                .filter(entry -> account.getId().equals(entry.getAccountId()))
                .toList();
        assertThat(entries).as("ledger entries of " + account.getIban()).hasSize(1);
        return entries.get(0);
    }

    private static TransferCommand toPartner(String key, String amount) {
        return new TransferCommand(key, TestIbans.A, TestIbans.PARTNER, "Olena Kovalenko", new BigDecimal(amount),
                "UAH", "Rent");
    }

    private static TransferCommand command(String key, String fromIban, String toIban, String amount,
            String description) {
        return new TransferCommand(key, fromIban, toIban, amount == null ? null : new BigDecimal(amount), "UAH",
                description);
    }

    private static Transfer withId(Transfer transfer) {
        if (transfer.getId() == null) {
            ReflectionTestUtils.setField(transfer, "id", UUID.randomUUID());
        }
        return transfer;
    }
}
