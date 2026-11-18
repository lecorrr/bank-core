package dev.tkachuk.bank.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One line of an account statement. Entries are only ever inserted, never changed, so the sum of an
 * account's entries always explains its balance. A transfer writes two: a DEBIT and a CREDIT.
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "transfer_id")
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 20)
    private EntryType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private EntryDirection direction;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(nullable = false, length = 140)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Money that came into the account.
     *
     * @param balanceAfter the account balance after the credit was applied
     * @param transferId   the transfer this entry belongs to, or null for a deposit
     */
    public static LedgerEntry credit(Long accountId, BigDecimal amount, BigDecimal balanceAfter, EntryType type,
            UUID transferId, String description, Instant createdAt) {
        return new LedgerEntry(accountId, EntryDirection.CREDIT, amount, balanceAfter, type, transferId,
                description, createdAt);
    }

    /**
     * Money that left the account.
     *
     * @param balanceAfter the account balance after the debit was applied
     * @param transferId   the transfer this entry belongs to
     */
    public static LedgerEntry debit(Long accountId, BigDecimal amount, BigDecimal balanceAfter, EntryType type,
            UUID transferId, String description, Instant createdAt) {
        return new LedgerEntry(accountId, EntryDirection.DEBIT, amount, balanceAfter, type, transferId,
                description, createdAt);
    }

    private LedgerEntry(Long accountId, EntryDirection direction, BigDecimal amount, BigDecimal balanceAfter,
            EntryType type, UUID transferId, String description, Instant createdAt) {
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.type = type;
        this.transferId = transferId;
        this.description = description;
        this.createdAt = createdAt;
    }
}
