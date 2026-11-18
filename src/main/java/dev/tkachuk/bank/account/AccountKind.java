package dev.tkachuk.bank.account;

/**
 * Whose account it is. Customers own CUSTOMER accounts; the other two are the bank's own, which a transfer to
 * another bank passes through (see InternalAccounts). Only CUSTOMER accounts are visible through the API.
 */
public enum AccountKind {
    CUSTOMER,
    /** Holds the money of transfers to other banks until those banks answer. */
    TRANSIT,
    /** Stands for the bank's account at the National Bank, through which other banks are paid. */
    SETTLEMENT
}
