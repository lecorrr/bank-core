package dev.tkachuk.bank.account;

/** Only CUSTOMER accounts are visible through the API; the other two are the bank's own. */
public enum AccountKind {
    CUSTOMER,
    // holds transfers to other banks until they answer
    TRANSIT,
    // the bank's account at the National Bank; may go below zero
    SETTLEMENT
}
