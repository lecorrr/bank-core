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

/** A movement of money. Customer transfers are kept by Idempotency-Key, payments from other banks by UETR. */
@Entity
@Table(name = "transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", unique = true, length = 100)
    private String idempotencyKey;

    @Column(unique = true)
    private UUID uetr;

    @Column(name = "from_iban", nullable = false, length = 34)
    private String fromIban;

    @Column(name = "from_name", length = 140)
    private String fromName;

    @Column(name = "to_iban", nullable = false, length = 34)
    private String toIban;

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

    @Column(name = "status_report_id", length = 35)
    private String statusReportId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rejection_reason", length = 30)
    private IncomingTransferRejection rejectionReason;

    public static Transfer completed(TransferCommand command, Instant createdAt) {
        return of(command, TransferStatus.COMPLETED, createdAt);
    }

    public static Transfer pending(TransferCommand command, Instant createdAt) {
        return of(command, TransferStatus.PENDING, createdAt);
    }

    public static Transfer incomingCredited(IncomingTransferCommand payment, Instant createdAt) {
        return incoming(payment, TransferStatus.COMPLETED, null, createdAt);
    }

    public static Transfer incomingRejected(IncomingTransferCommand payment, IncomingTransferRejection reason,
            Instant createdAt) {
        return incoming(payment, TransferStatus.REJECTED, Objects.requireNonNull(reason, "reason"), createdAt);
    }

    private static Transfer incoming(IncomingTransferCommand payment, TransferStatus status,
            IncomingTransferRejection reason, Instant createdAt) {
        Transfer transfer = new Transfer();
        transfer.uetr = payment.uetr();
        transfer.fromIban = payment.fromIban();
        transfer.fromName = payment.fromName();
        transfer.toIban = payment.toIban();
        transfer.toName = payment.toName();
        transfer.amount = payment.amount();
        transfer.currency = payment.currency();
        transfer.description = payment.description();
        transfer.status = status;
        transfer.rejectionReason = reason;
        transfer.createdAt = createdAt;
        return transfer;
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

    void settle(String reportId) {
        answer(TransferStatus.COMPLETED, reportId);
    }

    void reject(String reportId) {
        answer(TransferStatus.REJECTED, reportId);
    }

    private void answer(TransferStatus newStatus, String reportId) {
        if (!isPending()) {
            throw new IllegalStateException(
                    "Transfer " + id + " is already " + status + ", cannot become " + newStatus);
        }
        status = newStatus;
        statusReportId = reportId;
    }

    // The same request again: same accounts, payee, amount (by value), currency and description.
    public boolean hasSamePayloadAs(TransferCommand command) {
        return fromIban.equals(command.fromIban())
                && toIban.equals(command.toIban())
                && Objects.equals(toName, command.toName())
                && command.amount() != null && amount.compareTo(command.amount()) == 0
                && currency.equals(command.currency())
                && description.equals(command.description());
    }
}
