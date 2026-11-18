package dev.tkachuk.bank.ledger;

/** Why a balance changed. */
public enum EntryType {
    DEPOSIT,
    /** A transfer: between two customers, or from a customer to the transit account for another bank. */
    TRANSFER,
    /** The other bank accepted a transfer: its money moves from the transit account to the settlement account. */
    SETTLEMENT,
    /** The other bank refused a transfer: its money goes back from the transit account to the customer. */
    REVERSAL
}
