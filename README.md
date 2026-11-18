# bank-core

A small core-banking service: customer accounts, deposits, money transfers within the bank, to other Ukrainian
banks and from them, and bulk payment files in JSON or ISO 20022 XML. Payments between banks go through
[bank-integration](https://github.com/lecorrr/bank-integration), which carries them as ISO 20022 messages.

**Status: 0.5.0.** Everything listed below is in place, and every build checks it with unit and integration
tests and an 80 % line-coverage gate.

What it covers:

- **Contract-first REST API**: OpenAPI 3 (`src/main/resources/static/openapi/bank-core.yaml`) generates
  the Spring interfaces and DTOs; controllers implement them. Errors follow RFC 9457 problem details.
- **PostgreSQL done carefully**: Flyway migrations, transactions, `SELECT ... FOR UPDATE` with a fixed
  lock order, optimistic `@Version`, constraints as the last line of defence.
- **Payments that cannot run twice**: Idempotency-Key header, double-entry ledger, exact `BigDecimal`
  money.
- **Transfers to other banks**: PENDING until the other bank answers, with the money held in a transit account;
  a transactional outbox (Spring Modulith) sends the transfer to Kafka, and the answer coming back from Kafka
  completes it or gives the money back, with new ledger entries either way.
- **Payments from other banks**: a payment a customer of another bank sends comes in from Kafka and is decided
  once, by its UETR: credited from the settlement account, which stands for the bank's position at the National
  Bank, or refused (no such account here, or one in another currency). The decision goes back through the outbox,
  and a copy of the payment gets the same decision again.
- **Payment files**: one upload pays many creditors. JSON is checked against a JSON Schema and a simplified
  ISO 20022 `pain.001` against its XSD, with every problem reported at once; an XML file with a DOCTYPE is
  refused outright, which rules out XXE. Each payment is its own transfer, so uploading a file again pays
  nothing twice.
- **Tests that prove it**: unit tests with Mockito, `@WebMvcTest` slices, integration tests against a real
  PostgreSQL and Kafka with Testcontainers, a concurrency test, architecture rules, a JaCoCo coverage gate.
- **Delivery**: Docker image, docker compose, a Jenkins pipeline.

Stack: Java 21, Spring Boot 4.1, Spring Data JPA (Hibernate 7), PostgreSQL 17, Flyway, Spring Kafka,
Spring Modulith 2.1 (event publication registry), MapStruct, Lombok, networknt json-schema-validator,
Testcontainers 2, JUnit 6, Maven.

## Set up (Arch Linux or Ubuntu)

From the project directory:

```
bash scripts/setup.sh --check
```

It reports what is missing (JDK 21, Maven, Docker) with the command that fixes each item, and changes
nothing. Install what it lists, then run it without `--check`: it creates the Maven wrapper (`./mvnw`) and
runs the full build with every test. If something fails, its final summary says what.

## Run it

Start PostgreSQL in Docker (port 5434 on localhost, data in the `bank-core_bank-db` volume). If 5434 is taken,
`export BANK_DB_PORT=<free port>` first; compose and the app both read it:

```
docker compose up -d postgres
```

Run the application with four demo accounts:

```
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

Or run both in Docker; build the jar first:

```
./mvnw -DskipTests package
```

```
docker compose up --build
```

Once it is up, the log says which build is running (`bank-core 0.5.0 started`). Swagger UI is at
<http://localhost:8080/swagger-ui.html> and health at <http://localhost:8080/actuator/health>.

Payments to and from other banks need bank-integration's stack, which has the Kafka bank-core talks to
(`KAFKA_BOOTSTRAP_SERVERS`, `localhost:9092` by default). Start it from that repository with
`docker compose up -d --build`, then run bank-core with `spring-boot:run` as above. Without Kafka everything else
works; transfers to other banks then wait in the outbox, payments from them wait on the topic, and the log warns
every 30 seconds that Kafka is not there. The Docker version of bank-core cannot reach that Kafka, so use
`spring-boot:run` for them.

## Try it

The demo profile opens four accounts: `UA843999990000000000000000001` (10 000 UAH),
`UA573999990000000000000000002` (2 500 UAH), `UA303999990000000000000000003` (0 UAH) and
`UA033999990000000000000000004` (1 000 USD).

List the accounts:

```
curl -sS localhost:8080/api/v1/accounts
```

Open an account. Spaces and lower case are fine; the answer and its `Location` use the compact IBAN:

```
curl -sSi -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' -d '{"iban": "ua21 3223 1300 0002 6007 2335 6600 1", "holderName": "Artem Tkachuk", "currency": "UAH"}'
```

Deposit 500 UAH:

```
curl -sS -X POST localhost:8080/api/v1/accounts/UA843999990000000000000000001/deposits -H 'Content-Type: application/json' -d '{"amount": 500.00, "description": "Salary"}'
```

Transfer 150 UAH. Send the same command twice: the second answer has `Idempotent-Replayed: true` and no money
moves:

```
curl -sSi -X POST localhost:8080/api/v1/transfers -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' -d '{"fromIban": "UA843999990000000000000000001", "toIban": "UA573999990000000000000000002", "amount": 150.00, "currency": "UAH", "description": "Rent"}'
```

Transfer 150 UAH to another bank, with bank-integration running. `388888` is the partner bank its simulator
plays; such a transfer needs `toName`, and starts `PENDING`:

```
curl -sSi -X POST localhost:8080/api/v1/transfers -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-2' -d '{"fromIban": "UA843999990000000000000000001", "toIban": "UA643888880000000000000000777", "toName": "Olena Kovalenko", "amount": 150.00, "currency": "UAH", "description": "Rent"}'
```

A moment later the partner bank has accepted it, and `GET /api/v1/transfers/{id}` with the `id` from that answer
shows `COMPLETED`. The partner bank refuses payments to the closed account `UA843888880000000000000000999`:
a transfer there ends `REJECTED`, and the payer's statement shows the money coming back (`REVERSAL`).

Receive 250 UAH from a customer of the partner bank, with bank-integration running: its simulator sends the
payment (answering `202` with the payment's `uetr`), and a moment later the payee's statement shows it as an
`INCOMING` entry:

```
curl -sS -w '\n' -X POST localhost:8083/api/payments -H 'Content-Type: application/json' -d '{"toIban": "UA843999990000000000000000001", "toName": "Artem Demo", "amount": 250.00, "description": "For the concert tickets"}'
```

`GET localhost:8083/api/payments/{uetr}` shows what bank-core's bank answered the partner bank: `ACSC`. A payment
to `UA733999990000000000000000005`, an account this bank does not keep, is refused (`RJCT` with `AC01`), and one to
the dollar account `UA033999990000000000000000004` too (`CURR`); neither moves any money.

The bank's own accounts and the outbox, straight from the database. After payments in, the settlement account is
below zero: it is the bank's position at the National Bank, not anyone's debt:

```
docker compose exec postgres psql -U bank -d bank -c "SELECT iban, kind, balance FROM accounts WHERE kind <> 'CUSTOMER'" -c "SELECT event_type, status, publication_date FROM event_publication"
```

Upload a payment file, in JSON or as a `pain.001` XML. The answer lists each payment as accepted or rejected
with the reason; uploading the same file again marks them replayed and moves no money:

```
curl -sS -F file=@samples/payments-demo.json localhost:8080/api/v1/payment-batches
```

```
curl -sS -F file=@samples/payments-demo.xml localhost:8080/api/v1/payment-batches
```

See the statement:

```
curl -sS localhost:8080/api/v1/accounts/UA843999990000000000000000001/statement
```

## Tests

```
./mvnw verify
```

runs unit tests (`*Test`, Surefire), then integration tests (`*IT`, Failsafe, against PostgreSQL and Kafka in
Docker, so Docker must be running), then the coverage report (`target/site/jacoco/index.html`) and the
coverage gate, which fails the build below 80 % of lines covered.

One test class at a time; `-Djacoco.skip=true` keeps the coverage gate from failing a partial run:

```
./mvnw test -Dtest=TransferServiceTest -Djacoco.skip=true
```

```
./mvnw verify -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=PaymentBatchIT -Djacoco.skip=true
```

## Layout

```
src/main/java/dev/tkachuk/bank/
  common/        Amounts, errors, IBAN, the clock, web filter and error handler
  ledger/        ledger entries (statement lines)
  account/       accounts, deposits, statements
  transfer/      transfers and idempotency; finishing a transfer to another bank; payments from other banks
  paymentfile/   JSON and XML payment files, batch processing
  interbank/     Kafka events to and from bank-integration
src/main/resources/
  static/openapi/bank-core.yaml   the API contract (source of truth)
  db/migration/                   Flyway schema, the bank's own accounts, the outbox table; db/demo/ holds demo data
  schemas/                        JSON Schema and XSD for payment files
src/test/                         tests, sample payment files
scripts/setup.sh                  machine setup and first build
compose.yaml, Dockerfile          local PostgreSQL and the app image
Jenkinsfile, ci/jenkins/          CI pipeline and a local Jenkins (docs/jenkins.md)
```

[ARCHITECTURE.md](ARCHITECTURE.md) has the module rules and how to extend the service.
