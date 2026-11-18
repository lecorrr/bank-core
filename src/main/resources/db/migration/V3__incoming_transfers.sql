-- bank-core 0.5: payments from customers of other banks, which bank-integration brings in (flow B).

-- The settlement account stands for the bank's account at the National Bank: every payment in takes money from it
-- and every payment out (once settled) brings money to it, so it can go below zero, which is no customer's debt.
-- Every other account keeps its floor at zero.
ALTER TABLE accounts DROP CONSTRAINT accounts_balance_check;
ALTER TABLE accounts
    ADD CONSTRAINT accounts_balance_check CHECK (balance >= 0 OR kind = 'SETTLEMENT');

-- The payer of a payment from another bank holds no account here.
ALTER TABLE transfers DROP CONSTRAINT transfers_from_iban_fkey;

ALTER TABLE transfers
    -- The payer's name, as the payment from the other bank gives it; none for a customer's own transfer.
    ADD COLUMN from_name VARCHAR(140),
    -- The unique end-to-end reference the other bank gave the payment: a copy of it finds its transfer here, and
    -- is not credited twice.
    ADD COLUMN uetr UUID UNIQUE,
    -- Why this bank refused a payment from another bank: UNKNOWN_ACCOUNT or CURRENCY_MISMATCH.
    ADD COLUMN rejection_reason VARCHAR(30),
    ALTER COLUMN idempotency_key DROP NOT NULL,
    -- A transfer is either a customer's request, under its Idempotency-Key, or a payment from another bank,
    -- under its UETR; never both, never neither.
    ADD CONSTRAINT transfers_one_origin CHECK ((idempotency_key IS NULL) <> (uetr IS NULL)),
    ADD CONSTRAINT transfers_rejection_reason_known
        CHECK (rejection_reason IN ('UNKNOWN_ACCOUNT', 'CURRENCY_MISMATCH')),
    -- A payment from another bank that was refused has its reason, and nothing else has one.
    ADD CONSTRAINT transfers_rejection_reason_when_refused
        CHECK ((rejection_reason IS NOT NULL) = (uetr IS NOT NULL AND status = 'REJECTED'));
