package dev.tkachuk.bank.transfer;

/**
 * A transfer within the bank is COMPLETED at once. A transfer to another bank is PENDING until that bank answers:
 * COMPLETED when it accepts, REJECTED when it refuses and the money has gone back to the customer.
 */
public enum TransferStatus {
    PENDING,
    COMPLETED,
    REJECTED
}
