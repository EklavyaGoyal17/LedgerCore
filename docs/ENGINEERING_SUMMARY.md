# LedgerCore engineering handoff

LedgerCore now provides a working, simulated money movement backend with a real
double-entry ledger, atomic posting, concurrency-safe overdraft prevention, durable
idempotency, compensating reversals, ownership/role enforcement, asynchronous events
and independent reconciliation. No actual money or payment rails are involved.

## Architecture and data flow

The system is a Java 21 / Spring Boot modular monolith. Identities use JPA and
Hibernate; financial adapters use explicit JDBC for transparent SQL and lock
ordering. One Spring transaction manager and datasource coordinate the writes.

`TransferController` validates input and resolves the authenticated actor.
`TransferEngine` opens a READ COMMITTED transaction, reserves the durable request
key, locks participating accounts in UUID order and validates funds. It posts a
balanced journal, updates checked projections, stores a terminal business outcome,
audit record and outbox event, and saves the replayable response before commit.
Precommit technical failures roll back every effect. A committed insufficient-funds
outcome has a FAILED transfer and no journal. A lost postcommit response is recovered
using the same key.

An independent publisher sends due outbox entries with broker acknowledgment.
Unconfirmed sends retain the row for retry, so duplicates are possible. The consumer
commits a unique processed-event ID and audit projection together. Financial posting
does not wait for Kafka. Reconciliation uses a repeatable snapshot to detect accounting,
business-shape, projection, reversal, currency, audit and event discrepancies.

## Key source map

| File under `src/main` | Why to study it |
|---|---|
| `java/com/ledgercore/ledger/domain/Money.java` | Exact two-decimal money and supported currencies |
| `java/com/ledgercore/ledger/domain/Journal.java` | Entry-side and balanced-journal validation |
| `resources/db/migration/V1__financial_core.sql` | Constraints, deferred consistency checks and immutable posting guards |
| `java/com/ledgercore/accounts/AccountStore.java` | Ownership and deterministic row locking |
| `java/com/ledgercore/transfers/application/TransferEngine.java` | Atomic financial boundary, state transitions and fresh transaction retries |
| `java/com/ledgercore/transfers/application/Idempotency.java` | Durable concurrent key reservation and canonical fingerprint replay |
| `java/com/ledgercore/security/SecurityConfiguration.java` | JWT validators, BCrypt, roles and administrator bootstrap |
| `java/com/ledgercore/events/OutboxPublisher.java` | Acknowledgment window, backoff and propagated tracing |
| `java/com/ledgercore/events/EventConsumer.java` | Marker/effect atomicity and deduplication |
| `java/com/ledgercore/reconciliation/ReconciliationService.java` | Independent financial verification with no silent repair |

## Decisions and trade-offs

Customer funds are credit-normal liabilities; simulated deposits debit settlement
assets. Journals are immutable and reversals add history. Projections are checked
against the journal at commit, rather than being an independent balance authority.
Pessimistic row locks suit short funds decisions and hot-account contention; global
lock order avoids the common opposite-direction cycle. Bounded retries surround a
whole new transaction after lock acquisition/deadlock failures.

Idempotency relies on PostgreSQL uniqueness and one local commit, not a volatile
cache. Seven-day expiry metadata never releases a financial key. Redis provides
atomic rate limiting only; authentication fails closed during its outage and
authenticated business operations fail open. Outbox delivery is at least once,
with idempotent consumer effects; no end-to-end exactly-once or global ordering
claim is made. Eight ADRs describe rejected alternatives and their costs.

## Run and verify

```sh
docker compose up --build -d --wait --wait-timeout 300
mvn clean verify
python scripts/demo.py
```

Docker handles its own JDK/Maven; native Maven requires JDK 21. In this workspace
the API runs at **http://localhost:8088** because another project uses 8080. Set
BASE_URL accordingly for the demo. [VERIFICATION.md](VERIFICATION.md) contains exact
PowerShell setup and fresh-clone distinctions. Swagger is at
http://localhost:8088/swagger-ui/index.html; Grafana at http://localhost:3000.

Clean verification passed **44 tests with no failures/errors/skips** and produced
**88.40% JaCoCo instruction coverage**. The complete Compose stack started, API
acceptance passed, five k6 modes passed short smoke runs, live telemetry was checked
and actual query plans were captured. GitHub Actions is configured but was not run
remotely. Dependency vulnerability scanning and sustained benchmarking were not run.

## Study roadmap and interview preparation

Study exact money and journal validation first, then migration constraints, transfer
transactions and key reservation. Run the PostgreSQL races and compare them to the
lock timeline. Continue through JWT/ownership, outbox acknowledgment loss, consumer
deduplication and reconciliation fixtures. Use [the 14-guide roadmap](learning/README.md)
and [44 implementation-specific questions with answers](learning/interview-questions.md)
to rehearse a code walkthrough and explain evidence for each guarantee.

## Limits and further work

The detailed threat model and verification limits are documented. This local system
is not a certified or highly available banking deployment. Meaningful next steps
are separate migration/runtime database roles, restore/failover exercises, asymmetric
key rotation, measured query tuning and checkpoints, bounded reconciliation reports,
outbox/key archival preserving uniqueness, and a poison-event operations workflow.
Those extensions should preserve the existing financial invariants and add evidence
for new guarantees before being presented as operationally ready.
