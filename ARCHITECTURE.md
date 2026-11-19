# Architecture

One Spring Boot service, one PostgreSQL database, and Kafka to talk to bank-integration (Project 2). Small enough
for one Maven module: the boundaries are packages, and `LayeringTest` enforces them.

## Packages

| Package | Contains | May use |
|---|---|---|
| `common` | `Amounts`, `BankException` and `ErrorCode`, `Iban`, the `Clock`; `common.web`: request id filter, `ApiExceptionHandler` | nothing else of ours |
| `ledger` | `LedgerEntry`: append-only statement lines | `common` |
| `account` | `Account`, deposits, statements, the accounts API; `InternalAccounts` (transit and settlement) | `common`, `ledger` |
| `transfer` | `Transfer` and idempotency, the transfers API; `TransferSettlement` (answers from other banks); `IncomingTransfers` (payments from other banks) | `common`, `ledger`, `account` |
| `paymentfile` | file formats, readers, batch processing, the upload API | `common`, `transfer` |
| `interbank` | the Kafka contract with bank-integration | `common`, `transfer` |

The table is `MAY_DEPEND_ON` in `LayeringTest`. The same test also checks:

- only `*Controller` and `*Mapper` classes see the generated API types;
- only controllers and `common.web` import HTTP types;
- only `interbank` imports Kafka or Spring Modulith types. `TransferService` and `IncomingTransfers` publish
  plain Spring events, and `interbank` decides how they look on Kafka.

## Flows

A request:

```
HTTP -> RequestIdFilter -> generated *Api interface (@Valid) -> *Controller -> *Service (@Transactional)
     -> repository -> PostgreSQL
errors: BankException(ErrorCode) -> ApiExceptionHandler -> RFC 9457 problem with "code"
```

A payment file: the whole file is validated first (schema, unique end-to-end ids, header totals for XML), then
each payment runs as its own transfer with the key `messageId:endToEndId`.

A transfer to another bank (an IBAN whose bank code is not `bank.code`):

```
POST /transfers -> one transaction: customer DEBIT, TRANSIT CREDIT, Transfer PENDING,
                   InterbankTransferPending stored in event_publication (outbox)
after the commit -> TransferRequested on interbank.transfers (key: payer's IBAN)
the answer on interbank.transfer-statuses -> TransferSettlement:
    SETTLED:  TRANSIT DEBIT, SETTLEMENT CREDIT, Transfer COMPLETED
    REJECTED: TRANSIT DEBIT, customer CREDIT ("Returned: ..."), Transfer REJECTED
```

A payment from another bank:

```
IncomingTransferReceived on interbank.incoming-transfers (key: payee's IBAN) -> IncomingTransfers, one transaction:
    lock SETTLEMENT and the payee's account, then look up the UETR
    known UETR:         the same decision again
    no such customer:   REJECTED, UNKNOWN_ACCOUNT, no ledger entries
    other currency:     REJECTED, CURRENCY_MISMATCH, no ledger entries
    otherwise:          SETTLEMENT DEBIT, payee CREDIT, COMPLETED
    IncomingTransferDecision stored in the outbox -> IncomingTransferDecided (key: UETR)
```

## Rules

- **Money** is `BigDecimal` with two decimals (`Amounts.requireValid`), compared with `compareTo`, never `double`.
- **Balances** change only through `Account.credit` and `Account.debit`, each with a `LedgerEntry` in the same
  transaction. Ledger rows are never updated or deleted.
- **Locks**: two accounts are always locked through `LockedAccounts`, the smaller IBAN first. Answers lock the
  transfer row first; payments in lock the settlement account before the UETR lookup. The database checks
  `balance >= 0` (except SETTLEMENT), unique idempotency keys and unique UETRs.
- **Events** arrive at least once, so repeats are harmless: an answer applies only to a PENDING transfer, and a
  repeated payment gets its first decision again.
- **Errors**: `ApiExceptionHandler.statusOf` is the only ErrorCode-to-HTTP mapping; its `switch` has no default,
  so a new code doesn't compile until it has a status.
- **Time** comes from the `Clock` bean, never `Instant.now()`.
- **The contract** `bank-core.yaml` changes first; the build regenerates the interfaces.
- **Configuration** per machine comes from environment variables (`DB_URL`, `DB_USER`, `DB_PASSWORD`,
  `KAFKA_BOOTSTRAP_SERVERS`, `SPRING_PROFILES_ACTIVE`).
- **Logs** carry the request id; in Docker they are ECS JSON.

## Decisions

- **Two internal accounts.** TRANSIT holds exactly the PENDING transfers; SETTLEMENT stands for the bank's
  account at the National Bank and is what step 7 reconciles. Both are created by the V2 migration, hidden from
  the API and checked at startup.
- **Outbox with Spring Modulith** (event publication registry over JDBC, Kafka externalization), no Kafka
  Connect. Delivered events are deleted; failed ones are resent every 30 s by `FailedEventResubmission`.
- **Kafka is optional at runtime**: without it bank-core serves everything else.
- **Unreadable events are logged and skipped**, since no retry fixes them. Other failures (the database is down)
  are retried every 5 s, forever: skipping would leave a transfer PENDING or a payment without a decision.
- **The settlement account may go below zero**: it's the bank's position at the National Bank, not a debt.
- **bank-integration checks the message, bank-core the account**: bank, currency format and amount are checked
  before bank-core sees a payment; whether the account exists and in which currency only bank-core knows.
- **Hot rows**: every interbank transfer locks TRANSIT and every payment in locks SETTLEMENT. Fine at this size.
- **Released migrations are never edited**: Flyway checksums them. A schema change is the next `V` file.

## Extending it

- **Endpoint**: add it to `bank-core.yaml`, run `./mvnw compile`, implement it in the controller, add a
  `@WebMvcTest` and an `*IT`.
- **Error**: add the `ErrorCode` and its status in `ApiExceptionHandler.statusOf`, then a test.
- **Payment file format**: a `PaymentFileFormat` value and a `PaymentFileReader` component.
- **Package**: its own entity, service and controller, plus a line in `MAY_DEPEND_ON` and in the table above.
