# Architecture

One Spring Boot service, one PostgreSQL database, and Kafka to talk to bank-integration (Project 2). About
3,000 lines of main Java, 4,100 lines of tests, and 850 lines of contract, SQL and schemas. Small enough to keep
in one Maven module; the boundaries are packages, and `LayeringTest` enforces them.

## Modules

| Package | Owns | May use |
|---|---|---|
| `common` | `Amounts`, `BankException` and `ErrorCode`, `Iban`, the `Clock`; in `common.web` the request id filter and `ApiExceptionHandler` | nothing else of ours |
| `ledger` | `LedgerEntry`: append-only statement lines, one per balance change | `common` |
| `account` | `Account`, deposits, statements, the accounts API; `InternalAccounts`, the bank's own transit and settlement accounts | `common`, `ledger` |
| `transfer` | `Transfer`, idempotency, the transfers API; `TransferSettlement`, which finishes a transfer to another bank once it answers; `IncomingTransfers`, which credits or refuses a payment from another bank | `common`, `ledger`, `account` |
| `paymentfile` | file formats, the readers, batch processing, the upload API | `common`, `transfer` |
| `interbank` | the Kafka contract with bank-integration: `TransferRequested` and `IncomingTransferDecided` out through the outbox, `TransferStatusChanged` and `IncomingTransferReceived` in | `common`, `transfer` |

The table is `MAY_DEPEND_ON` in `LayeringTest`; change both together. Two more rules are tested there:

- Only `*Controller` and `*Mapper` classes see the generated API types (`dev.tkachuk.bank.api`). Services
  work with entities and commands, so a contract change stops at the controller.
- Only controllers and `common.web` import HTTP or servlet types. Business code never knows it is behind
  HTTP, which is why `PaymentBatchService` can later be driven by a message queue (Project 2).
- Only `interbank` imports Kafka or Spring Modulith types. `TransferService` publishes a plain Spring event,
  `InterbankTransferPending`, and `IncomingTransfers` another, `IncomingTransferDecision`; how they reach Kafka,
  and what they look like there, is `interbank`'s business.

`ledger` stores an account id, not an `Account` relation, so that it does not depend on `account`.

## How a request flows

```
HTTP -> RequestIdFilter -> generated *Api interface (routes, @Valid) -> *Controller
     -> *Service (@Transactional, business rules) -> Spring Data repository -> PostgreSQL
errors: BankException(ErrorCode) -> ApiExceptionHandler -> RFC 9457 problem + "code"
```

A payment file:

```
upload -> PaymentBatchesController -> PaymentBatchService
       -> PaymentFileFormat.detect -> JsonPaymentFileReader | XmlPaymentFileReader -> PaymentBatch
       -> for each payment: TransferService.transfer (own transaction, key "messageId:endToEndId")
       -> PaymentBatchReport: accepted, rejected with error code, replayed
```

The whole file is validated before the first payment: against its schema (JSON Schema, or the XSD by a parser
that refuses any DOCTYPE, so no XXE), then for what a schema cannot say: unique end-to-end ids, in JSON at most
two decimals per amount (the XSD says that for XML), and in XML the group header's transaction count and control
sum. Then each payment is its own transaction, so one payment without money does not undo the others, and
uploading the file again pays nothing twice.

A transfer to another bank (an IBAN whose bank code is not `bank.code`):

```
POST /transfers -> TransferService.transfer, one transaction:
    customer DEBIT, TRANSIT CREDIT (type TRANSFER), Transfer PENDING,
    InterbankTransferPending -> Spring Modulith writes it to event_publication
after the commit: Modulith -> TransferRequested on Kafka interbank.transfers (key: payer's IBAN)
    -> row deleted once Kafka has it; a failed send stays FAILED and is sent again every 30 s
bank-integration -> pacs.008 -> the other bank -> pacs.002 -> bank-integration
Kafka interbank.transfer-statuses (key: transfer id) -> TransferStatusListener -> TransferSettlement:
    SETTLED:  TRANSIT DEBIT, SETTLEMENT CREDIT (type SETTLEMENT), Transfer COMPLETED
    REJECTED: TRANSIT DEBIT, customer CREDIT (type REVERSAL, "Returned: ..."), Transfer REJECTED
```

Every step writes new rows; nothing is updated in the ledger or deleted, so the customer's statement shows the
money leaving and, after a refusal, coming back. The transit account always holds exactly the money of the
PENDING transfers, which makes a simple reconciliation check.

A payment from a customer of another bank (flow B of bank-integration):

```
the other bank -> pacs.008 -> bank-integration
Kafka interbank.incoming-transfers (key: payee's IBAN) -> IncomingTransferListener -> IncomingTransfers, one
transaction:
    lock SETTLEMENT and the payee's account (IBAN order), then look for a transfer with this UETR:
    found:              its decision again (a copy of the payment)
    no such customer:   Transfer REJECTED, UNKNOWN_ACCOUNT, no ledger entries
    another currency:   Transfer REJECTED, CURRENCY_MISMATCH, no ledger entries
    otherwise:          SETTLEMENT DEBIT, payee CREDIT (type INCOMING), Transfer COMPLETED
    IncomingTransferDecision -> Spring Modulith writes it to event_publication
after the commit: Modulith -> IncomingTransferDecided on Kafka interbank.incoming-transfer-decisions (key: UETR)
bank-integration -> pacs.002 ACSC, or RJCT with AC01 or CURR -> the other bank
```

Such a transfer has the payer's IBAN at the other bank in `from_iban` and the payer's name in `from_name`, and is
kept under its UETR instead of an Idempotency-Key; the V3 migration requires exactly one of the two. A refusal is a
decision, not an error: it is kept, so a copy of the payment is refused again even if the account exists by then.

## Rules the code keeps

- **Money** is `BigDecimal` with exactly two decimals, checked by `Amounts.requireValid`, compared with
  `compareTo`. Never `double`.
- **Balances** change only through `Account.credit` and `Account.debit`, and every change writes a
  `LedgerEntry` with the balance after it, in the same transaction.
- **Locks**: code that changes balances locks its two accounts through `LockedAccounts`, the smaller IBAN
  first, whichever way the money goes; that includes the transit and settlement accounts. An answer from
  another bank locks its transfer row first (`findByIdForUpdate`), so a repeated answer waits for the first. A
  payment from another bank locks the settlement account (and the payee's, in IBAN order) before it looks for its
  UETR, so a copy that comes at the same time waits there and then finds the first one's transfer. The database
  also checks `balance >= 0` on every account but SETTLEMENT, unique idempotency keys and unique UETRs.
- **Events**: delivered at least once in both directions, so both sides make a repeat harmless: bank-core
  applies an answer only to a PENDING transfer, a replayed transfer request publishes nothing, and a copy of a
  payment from another bank publishes the decision it got the first time.
- **Errors**: business code throws `BankException` with an `ErrorCode`. `ApiExceptionHandler.statusOf` is
  the only place that maps codes to HTTP statuses; its `switch` has no default, so a new code does not
  compile until it has a status.
- **Time** comes from the `Clock` bean, never `Instant.now()`, so tests can fix it.
- **The contract** is `bank-core.yaml`. Change it first; the build regenerates the interfaces and the
  compiler shows every controller that must follow. Swagger UI shows the same file.
- **Configuration** that differs per machine comes from environment variables (`DB_URL`, `DB_USER`,
  `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `SPRING_PROFILES_ACTIVE`); defaults are in `application.yml`.
- **Logs** carry `requestId` from `X-Request-Id`. In Docker they are ECS JSON, ready for ELK.

## Decisions

- **Two internal accounts, not one.** The money of a transfer to another bank waits in TRANSIT until that bank
  answers, then moves on to SETTLEMENT, which stands for the bank's account at the National Bank. With one account
  "sent" and "settled" money would mix; with two, TRANSIT equals the PENDING transfers and SETTLEMENT is what step 7
  reconciles against the National Bank's statement. Both are rows in `accounts` with `kind`, created by the V2
  migration, hidden from the API, and checked at startup (`InternalAccounts`).
- **The outbox is Spring Modulith's event publication registry**, kept in PostgreSQL through JDBC, with Kafka
  externalisation. No Kafka Connect, unlike Debezium. Delivered events are deleted (`completion-mode: delete`), so
  `event_publication` shows exactly what is still to be sent. A send that fails is marked FAILED and retried every
  30 seconds by `FailedEventResubmission`; one that a crash left half done is marked FAILED by Modulith's staleness
  check and then retried the same way. Only these Modulith modules are used, not its module checks.
- **Kafka is optional at runtime.** Without it bank-core starts and serves everything else; transfers to other banks
  wait in the outbox and their answers wait on the topic.
- **Events that cannot be read are skipped and logged** (`InterbankJson`); no retry makes them readable. An
  unreadable payment from another bank is logged as an error: it stays without an answer, so a person has to look.
  An event that fails for another reason (the database is down) is retried every 5 seconds and never skipped,
  because skipping an answer would leave its transfer PENDING for good, and skipping a payment would leave it
  without a decision. bank-integration validates every event before it publishes it, so an unreadable one means a
  bug on one side.
- **The settlement account can go below zero.** It stands for the bank's account at the National Bank: payments
  out add to it once settled, payments in take from it, and a bank with more coming in than going out is below
  zero there, which is no customer's debt. `Account.debit` and the database's balance check make that exception
  for SETTLEMENT only.
- **bank-core decides on the account, bank-integration on the message.** Whether a payment's bank, currency and
  account number fit our bank at all is bank-integration's check (`IncomingChecks`), made before bank-core sees
  it; whether the account exists and is kept in that currency only bank-core can say.
- **The transit and settlement accounts are hot rows**: every transfer to another bank locks the transit account,
  and every payment from one the settlement account. Fine at this size; at bank scale they would be split, or their
  balances kept from the entries instead of on the row.

## Extending it

- **New endpoint**: add it to `bank-core.yaml`, run `./mvnw compile`, implement the new interface method
  in the controller, add a `@WebMvcTest` and an `*IT`.
- **New error**: add the `ErrorCode`, then its status in `ApiExceptionHandler.statusOf` (the compiler
  insists), then a test that triggers it.
- **New payment file format** (say CSV): add a `PaymentFileFormat` value and its detection, write a
  `PaymentFileReader` `@Component`. `PaymentBatchService` refuses to start if a format has no reader.
- **New table or column**: the next Flyway file, `V4__....sql`; never edit one that has run anywhere.
  Hibernate validates the mapping at startup (`ddl-auto: validate`).
- **A new module** (say `card`): a package with its own entity, service and controller, a line in
  `MAY_DEPEND_ON` and in the table above.
