-- Demo data for the "demo" profile only. Safe to re-run: existing accounts are left alone.
INSERT INTO accounts (iban, holder_name, currency, balance, version, created_at)
VALUES ('UA843999990000000000000000001', 'Artem Demo', 'UAH', 10000.00, 0, now()),
       ('UA573999990000000000000000002', 'Olena Kovalenko', 'UAH', 2500.00, 0, now()),
       ('UA303999990000000000000000003', 'Supplier LLC', 'UAH', 0.00, 0, now()),
       ('UA033999990000000000000000004', 'Artem Demo USD', 'USD', 1000.00, 0, now())
ON CONFLICT (iban) DO NOTHING;

-- Every balance must be explained by the ledger, so opening balances get a deposit entry.
INSERT INTO ledger_entries (account_id, transfer_id, entry_type, direction, amount, balance_after, description, created_at)
SELECT a.id, NULL, 'DEPOSIT', 'CREDIT', a.balance, a.balance, 'Opening deposit', now()
FROM accounts a
WHERE a.balance > 0
  AND NOT EXISTS (SELECT 1 FROM ledger_entries le WHERE le.account_id = a.id);
