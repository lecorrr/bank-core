-- bank-core 0.8: the customer who holds an account. bank-open-api asks for it, so a customer can give a provider
-- access to their own accounts only. Accounts opened before 0.8 have none until someone sets it.
ALTER TABLE accounts
    ADD COLUMN customer_id VARCHAR(36),
    -- The bank's own accounts belong to no customer.
    ADD CONSTRAINT accounts_internal_without_customer CHECK (kind = 'CUSTOMER' OR customer_id IS NULL);

CREATE INDEX accounts_customer_idx ON accounts (customer_id);
