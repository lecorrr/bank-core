package dev.tkachuk.bank.ledger;

public enum EntryType {
    DEPOSIT,
    TRANSFER,
    // the other bank accepted: transit to settlement
    SETTLEMENT,
    // the other bank refused: transit back to the customer
    REVERSAL,
    // a payment from another bank: settlement to the customer
    INCOMING
}
