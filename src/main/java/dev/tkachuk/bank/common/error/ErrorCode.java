package dev.tkachuk.bank.common.error;

/** Machine-readable error codes. ApiExceptionHandler maps each one to an HTTP status in one place. */
public enum ErrorCode {
    ACCOUNT_NOT_FOUND,
    TRANSFER_NOT_FOUND,
    ACCOUNT_ALREADY_EXISTS,
    IDEMPOTENCY_KEY_REUSED,
    CONFLICT,
    INVALID_IBAN,
    INVALID_AMOUNT,
    INVALID_PAYMENT_FILE,
    UNSUPPORTED_FILE_FORMAT,
    SAME_ACCOUNT,
    CURRENCY_MISMATCH,
    INSUFFICIENT_FUNDS,
    /** A transfer to another bank without the payee's name, which the payment message must carry. */
    BENEFICIARY_NAME_REQUIRED,
    /** A transfer abroad: bank-core pays only within Ukraine, through the National Bank's system. */
    UNSUPPORTED_DESTINATION
}
