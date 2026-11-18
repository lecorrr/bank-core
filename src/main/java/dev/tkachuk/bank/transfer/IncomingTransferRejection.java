package dev.tkachuk.bank.transfer;

/** Why this bank refused a payment from another bank. No money moves; the other bank gives it back to the payer. */
public enum IncomingTransferRejection {
    /** The payee's IBAN is not a customer account of this bank. */
    UNKNOWN_ACCOUNT,
    /** The payee's account is kept in another currency than the payment's. */
    CURRENCY_MISMATCH
}
