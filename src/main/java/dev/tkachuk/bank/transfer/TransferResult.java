package dev.tkachuk.bank.transfer;

/**
 * @param replayed true when the Idempotency-Key was seen before and the original transfer is returned
 *                 instead of moving money again
 */
public record TransferResult(Transfer transfer, boolean replayed) {
}
