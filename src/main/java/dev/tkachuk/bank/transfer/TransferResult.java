package dev.tkachuk.bank.transfer;

public record TransferResult(Transfer transfer, boolean replayed) {
}
