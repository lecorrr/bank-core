package dev.tkachuk.bank.transfer;

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
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A movement of money from a customer's account. Within the bank it is COMPLETED at once, with two LedgerEntry
 * rows. To another bank it is PENDING until that bank answers, and the answer adds two more entries (see
 * TransferSettlement). Every entry carries the transfer's id.
 */
@Entity
@Table(name = "transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "from_iban", nullable = false, length = 34)
    private String fromIban;

    @Column(name = "to_iban", nullable = false, length = 34)
    private String toIban;

    /** The payee's name: always set for a transfer to another bank, whose payment message must carry it. */
    @Column(name = "to_name", length = 140)
    private String toName;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 140)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The MsgId of the other bank's status report (pacs.002) that settled or rejected the transfer. */
    @Column(name = "status_report_id", length = 35)
    private String statusReportId;

    /**
     * Records a transfer whose money has already moved. Pass a command whose IBANs are compact and whose
     * amount went through Amounts.requireValid. The id is assigned when the transfer is saved.
     */
    public static Transfer completed(TransferCommand command, Instant createdAt) {
        return of(command, TransferStatus.COMPLETED, createdAt);
    }

    /**
     * Records a transfer to another bank whose money has left the customer's account for the transit account,
     * where it waits for that bank's answer. Pass a command as for completed().
     */
    public static Transfer pending(TransferCommand command, Instant createdAt) {
        return of(command, TransferStatus.PENDING, createdAt);
    }

    private static Transfer of(TransferCommand command, TransferStatus status, Instant createdAt) {
        Transfer transfer = new Transfer();
        transfer.idempotencyKey = command.idempotencyKey();
        transfer.fromIban = command.fromIban();
        transfer.toIban = command.toIban();
        transfer.toName = command.toName();
        transfer.amount = command.amount();
        transfer.currency = command.currency();
        transfer.description = command.description();
        transfer.status = status;
        transfer.createdAt = createdAt;
        return transfer;
    }

    public boolean isPending() {
        return status == TransferStatus.PENDING;
    }

    /**
     * The other bank accepted the transfer. Only TransferSettlement calls this, after moving the money.
     *
     * @param reportId the MsgId of that bank's status report
     * @throws IllegalStateException when the transfer is not PENDING: it has had its answer already
     */
    void settle(String reportId) {
        answer(TransferStatus.COMPLETED, reportId);
    }

    /**
     * The other bank refused the transfer. Only TransferSettlement calls this, after returning the money.
     *
     * @param reportId the MsgId of that bank's status report
     * @throws IllegalStateException when the transfer is not PENDING: it has had its answer already
     */
    void reject(String reportId) {
        answer(TransferStatus.REJECTED, reportId);
    }

    private void answer(TransferStatus newStatus, String reportId) {
        if (!isPending()) {
            throw new IllegalStateException("Transfer " + id + " is " + status + ", so it cannot become " + newStatus);
        }
        status = newStatus;
        statusReportId = reportId;
    }

    /**
     * True when the command asks for exactly this transfer: the same accounts, payee name, amount, currency and
     * description.
     * Used for a repeated Idempotency-Key: the same payload means "replay the original", another payload means
     * IDEMPOTENCY_KEY_REUSED. Pass a normalised command, as TransferService does, so a retry typed differently
     * still matches. The idempotency key and the time are not part of the payload.
     */
    public boolean hasSamePayloadAs(TransferCommand command) {
        return fromIban.equals(command.fromIban())
                && toIban.equals(command.toIban())
                && Objects.equals(toName, command.toName())
                // By value: 150.0 and 150.00 are the same money, though BigDecimal.equals says they differ.
                && command.amount() != null && amount.compareTo(command.amount()) == 0
                && currency.equals(command.currency())
                && description.equals(command.description());
    }
}
