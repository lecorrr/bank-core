# bank-core

A small core-banking service: customer accounts, deposits, transfers within the bank, to other Ukrainian banks
and from them, and bulk payment files in JSON or ISO 20022 XML. Payments between banks go through
[bank-integration](https://github.com/lecorrr/bank-integration). This is Project 1 of three.

**Status: 0.7.0.** Every build runs the unit and integration tests and an 80 % line-coverage gate.

- **Contract-first REST API**: the OpenAPI file `src/main/resources/static/openapi/bank-core.yaml` generates the
  interfaces and DTOs; errors are RFC 9457 problem details with a `code`.
- **Money handled carefully**: `BigDecimal` amounts, a double-entry ledger, `SELECT ... FOR UPDATE` in a fixed lock
  order, optimistic locking, and an `Idempotency-Key` on every transfer.
- **Other banks**: a transfer to another bank waits in a transit account until that bank answers; payments from
  other banks are credited or rejected once per UETR. Events go out through a transactional outbox (Spring
  Modulith) and come back over Kafka.
- **Payment files**: JSON checked against a JSON Schema, `pain.001` XML against an XSD (DOCTYPE refused); each
  payment is its own transfer, so uploading a file again pays nothing twice.
- **One trace per payment**: every log line has the trace and span id (ECS JSON in Docker). A transfer's trace
  runs from the REST call through Kafka to bank-integration and back with the answer: the W3C `traceparent`
  travels in the record headers and is stored with the outbox event, so a resend continues the same trace.
- **Runs on Kubernetes**: a Helm chart (`charts/bank-core`) for the k3d cluster of
  [bank-open-api](https://github.com/lecorrr/bank-open-api), the open banking API in front of bank-core. The pod
  runs as a non-root user on a read-only file system, with probes on Spring Boot's liveness and readiness; Jenkins
  builds, pushes and deploys it ([docs/jenkins.md](docs/jenkins.md)).

Stack: Java 21, Spring Boot 4.1, Spring Data JPA, PostgreSQL 17, Flyway, Spring Kafka, Spring Modulith 2.1,
Micrometer Tracing with OpenTelemetry, MapStruct, Lombok, Testcontainers, JUnit, Maven, Helm, Jenkins. The design is in [ARCHITECTURE.md](ARCHITECTURE.md).

## Set up (Arch Linux or Ubuntu)

```
bash scripts/setup.sh --check
```

It lists what is missing (JDK 21, Maven, Docker) with the command for each and changes nothing. Install what it
lists, then run it without `--check`: it creates `./mvnw` and runs the full build.

## Build and test

```
./mvnw verify
```

Unit tests (`*Test`) run first, then the integration tests (`*IT`) against PostgreSQL and Kafka in Testcontainers,
then the coverage gate (report in `target/site/jacoco/index.html`). One test class at a time:

```
./mvnw test -Dtest=TransferServiceTest -Djacoco.skip=true
```

## Run

Start PostgreSQL (localhost:5434; set `BANK_DB_PORT` if that port is taken):

```
docker compose up -d postgres
```

Run the application with four demo accounts:

```
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

Or run both in Docker after building the jar:

```
./mvnw -DskipTests package
```

```
docker compose up --build
```

The log shows the running version (`bank-core 0.7.0 started`). Swagger UI is at
<http://localhost:8080/swagger-ui.html>, health at <http://localhost:8080/actuator/health>.

Payments with other banks need bank-integration's stack, which runs the Kafka on `localhost:9092`
(`KAFKA_BOOTSTRAP_SERVERS`). Start it from that repository, then run bank-core with `spring-boot:run`. Without
Kafka everything else works: outgoing transfers wait in the outbox and incoming payments wait on the topic.

## Try it

Demo accounts: `UA843999990000000000000000001` (10 000 UAH), `UA573999990000000000000000002` (2 500 UAH),
`UA303999990000000000000000003` (0 UAH) and `UA033999990000000000000000004` (1 000 USD).

```
curl -sS localhost:8080/api/v1/accounts
```

Open an account (spaces and lower case are accepted):

```
curl -sSi -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' -d '{"iban": "ua21 3223 1300 0002 6007 2335 6600 1", "holderName": "Artem Tkachuk", "currency": "UAH"}'
```

Deposit 500 UAH:

```
curl -sS -X POST localhost:8080/api/v1/accounts/UA843999990000000000000000001/deposits -H 'Content-Type: application/json' -d '{"amount": 500.00, "description": "Salary"}'
```

Transfer 150 UAH. Sent twice with the same key, the second answer has `Idempotent-Replayed: true` and no money
moves:

```
curl -sSi -X POST localhost:8080/api/v1/transfers -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-1' -d '{"fromIban": "UA843999990000000000000000001", "toIban": "UA573999990000000000000000002", "amount": 150.00, "currency": "UAH", "description": "Rent"}'
```

Transfer to the partner bank (bank code `388888`, with bank-integration running). It needs `toName` and starts
`PENDING`; a moment later `GET /api/v1/transfers/{id}` shows `COMPLETED`. To the closed account
`UA843888880000000000000000999` it ends `REJECTED` and the money comes back (`REVERSAL`).

```
curl -sSi -X POST localhost:8080/api/v1/transfers -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-2' -d '{"fromIban": "UA843999990000000000000000001", "toIban": "UA643888880000000000000000777", "toName": "Olena Kovalenko", "amount": 150.00, "currency": "UAH", "description": "Rent"}'
```

The same with a trace of your own: every log line about this transfer, in bank-core and in bank-integration,
then shows trace id `0af7651916cd43dd8448eb211c80319c`.

```
curl -sSi -X POST localhost:8080/api/v1/transfers -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-3' -H 'traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01' -d '{"fromIban": "UA843999990000000000000000001", "toIban": "UA643888880000000000000000777", "toName": "Olena Kovalenko", "amount": 150.00, "currency": "UAH", "description": "Rent"}'
```

Receive 250 UAH from the partner bank's customer; the payee's statement then shows an `INCOMING` entry. A payment
to `UA733999990000000000000000005` (no such account) or to the USD account is rejected and moves no money.

```
curl -sS -w '\n' -X POST localhost:8083/api/payments -H 'Content-Type: application/json' -d '{"toIban": "UA843999990000000000000000001", "toName": "Artem Demo", "amount": 250.00, "description": "For the concert tickets"}'
```

Upload a payment file; uploading it again marks every payment as replayed:

```
curl -sS -F file=@samples/payments-demo.json localhost:8080/api/v1/payment-batches
```

```
curl -sS -F file=@samples/payments-demo.xml localhost:8080/api/v1/payment-batches
```

The statement:

```
curl -sS localhost:8080/api/v1/accounts/UA843999990000000000000000001/statement
```

The bank's own accounts and the outbox. After payments in, the settlement account is below zero: it is the
bank's position at the National Bank.

```
docker compose exec postgres psql -U bank -d bank -c "SELECT iban, kind, balance FROM accounts WHERE kind <> 'CUSTOMER'" -c "SELECT event_type, status, publication_date FROM event_publication"
```

## Layout

```
src/main/java/dev/tkachuk/bank/
  common/        amounts, errors, IBAN, clock, current trace, request id filter, error handler
  ledger/        ledger entries
  account/       accounts, deposits, statements
  transfer/      transfers, settlement of transfers to other banks, payments from other banks
  paymentfile/   JSON and XML payment files
  interbank/     Kafka events to and from bank-integration
src/main/resources/
  static/openapi/bank-core.yaml   the API contract
  db/migration/, db/demo/         Flyway migrations and demo data
  schemas/                        JSON Schema and XSD for payment files
scripts/setup.sh                  machine setup and first build
charts/bank-core/                 Helm chart for the k3d cluster of bank-open-api
Jenkinsfile, ci/jenkins/          CI pipeline, deploy to k3d, and a local Jenkins (docs/jenkins.md)
```
