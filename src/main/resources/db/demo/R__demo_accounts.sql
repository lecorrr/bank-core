-- Demo data for the "demo" profile only. Safe to re-run: existing accounts are left alone.
-- Customer C1001 holds two accounts; bank-open-api's test customer in Keycloak is C1001.
INSERT INTO accounts (iban, holder_name, currency, balance, version, created_at, customer_id)
VALUES ('UA843999990000000000000000001', 'Artem Demo', 'UAH', 10000.00, 0, now(), 'C1001'),
       ('UA573999990000000000000000002', 'Olena Kovalenko', 'UAH', 2500.00, 0, now(), 'C1002'),
       ('UA303999990000000000000000003', 'Supplier LLC', 'UAH', 0.00, 0, now(), 'C2001'),
       ('UA033999990000000000000000004', 'Artem Demo USD', 'USD', 1000.00, 0, now(), 'C1001')
ON CONFLICT (iban) DO NOTHING;

-- Demo accounts made before bank-core 0.8 get their customer.
UPDATE accounts
SET customer_id = demo.customer_id
FROM (VALUES ('UA843999990000000000000000001', 'C1001'),
             ('UA573999990000000000000000002', 'C1002'),
             ('UA303999990000000000000000003', 'C2001'),
             ('UA033999990000000000000000004', 'C1001')) AS demo (iban, customer_id)
WHERE accounts.iban = demo.iban
  AND accounts.customer_id IS NULL;

-- Every balance must be explained by the ledger, so opening balances get a deposit entry.
INSERT INTO ledger_entries (account_id, transfer_id, entry_type, direction, amount, balance_after, description, created_at)
SELECT a.id, NULL, 'DEPOSIT', 'CREDIT', a.balance, a.balance, 'Opening deposit', now()
FROM accounts a
WHERE a.balance > 0
  AND NOT EXISTS (SELECT 1 FROM ledger_entries le WHERE le.account_id = a.id);
