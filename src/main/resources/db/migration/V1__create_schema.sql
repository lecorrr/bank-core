CREATE TABLE accounts (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    iban        VARCHAR(34)    NOT NULL UNIQUE,
    holder_name VARCHAR(140)   NOT NULL,
    currency    VARCHAR(3)     NOT NULL CHECK (currency IN ('UAH', 'USD', 'EUR')),
    -- Last line of defence: even buggy code cannot take an account below zero.
    balance     NUMERIC(19, 2) NOT NULL CHECK (balance >= 0),
    version     BIGINT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ    NOT NULL
);

CREATE TABLE transfers (
    id              UUID PRIMARY KEY,
    -- UNIQUE makes a second execution of the same request impossible, even under concurrency.
    idempotency_key VARCHAR(100)   NOT NULL UNIQUE,
    from_iban       VARCHAR(34)    NOT NULL REFERENCES accounts (iban),
    to_iban         VARCHAR(34)    NOT NULL REFERENCES accounts (iban),
    amount          NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency        VARCHAR(3)     NOT NULL,
    description     VARCHAR(140)   NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL,
    CHECK (from_iban <> to_iban)
);

CREATE TABLE ledger_entries (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id    BIGINT         NOT NULL REFERENCES accounts (id),
    -- Checked at commit: the entries are written in the same transaction as their transfer, in any order.
    transfer_id   UUID REFERENCES transfers (id) DEFERRABLE INITIALLY DEFERRED,
    entry_type    VARCHAR(20)    NOT NULL,
    direction     VARCHAR(6)     NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount        NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    balance_after NUMERIC(19, 2) NOT NULL,
    description   VARCHAR(140)   NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL
);

CREATE INDEX ledger_entries_account_idx ON ledger_entries (account_id, id);
CREATE INDEX transfers_from_iban_idx ON transfers (from_iban);
CREATE INDEX transfers_to_iban_idx ON transfers (to_iban);
