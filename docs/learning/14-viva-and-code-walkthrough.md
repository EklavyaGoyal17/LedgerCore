# Viva and code walkthrough

## Concept in plain English

A useful walkthrough follows a real operation and explains the durable fact at
each boundary. Framework names are supporting details; the financial invariant
and its evidence should remain understandable without them.

## Why backend engineers need it

A reviewer can interrupt with a concurrent withdrawal, a missing response, a
database timeout or a broker outage. The answer should identify which transaction
owns the change and which test demonstrates recovery, rather than promising that
an exception handler makes a distributed operation atomic.

## LedgerCore implementation and relevant files

Read these files in order under `src/main/java/com/ledgercore`:

1. `ledger/domain/Money` and `Journal`: exact units, supported currencies and sides.
2. `transfers/TransferController`: validated wire request and authenticated actor.
3. `transfers/application/Idempotency`: unique reservation and canonical replay.
4. `accounts/AccountStore`: explicit deterministic lock acquisition.
5. `transfers/application/TransferEngine`: one local commit, narrow retry and outcomes.
6. `ledger/JournalPosting`: immutable accounting records; inspect V1/V2 constraints.
7. `events/EventRecorder`, `OutboxPublisher`, `EventConsumer`, `EventOperations`:
   publication, duplicates, quarantine and audited projection replay.
8. `reconciliation/ReconciliationService`: independent detective controls.
9. `common/MigrationRunner`, Compose and runtime grants: deployment trust boundary.

## Concrete fifteen-minute demonstration

Run the documented API demo. Select a completed transfer ID and its journal ID.
Show the two journal entries and explain why liability debits reduce customer
funds. Show the source statement and ledger-derived balance. Replay the same key
and demonstrate the same transfer ID. Change its amount and explain the 409.

Then open the repeated 800-from-1000 PostgreSQL race. Draw the lock timeline and
show why the waiting transaction sees 200. Explain why both accounts are locked
in the same global order. Select the real lock-timeout/deadlock tests and show
the exact narrow SQL states that receive a fresh transaction retry.

Run or inspect ProcessRecoveryIT: before-commit death leaves no movement;
after-commit death leaves one completed operation. Fresh JVMs replay the stored
result. Move to the broker outage test: the local financial commit survives,
while pending publication later recovers. A poison record instead reaches durable
quarantine and requires an audited consumer replay without moving money again.

Finish with a clean reconciliation report, the populated V1 upgrade fixture,
the logical restore drill and one connected trace. Use current verification
artifacts for actual counts and measurements; do not memorize obsolete numbers.

## Common mistakes

- Explaining annotations without identifying the transaction and lock boundaries.
- Treating HTTP 500 as proof of rollback or generating a new retry key automatically.
- Calling producer idempotence end-to-end exactly-once delivery.
- Describing a mock or H2 test as proof of PostgreSQL behavior.
- Presenting a scan exception as a patched library or a local restore as HA failover.
- Quoting performance without hardware, dataset, concurrency and error information.

## Failure scenarios

If Docker is unavailable, say which domain checks ran and which infrastructure
checks did not. If a migration fails, the backend must remain stopped and the
transaction must preserve prior accounting history. If an event cannot be
quarantined durably, its Kafka acknowledgment must not advance past lost evidence.
Use these cases to explain the limits of local atomicity and external delivery.

## Interview questions and answers

- **What is your strongest correctness evidence?** The implementation reasoning,
  PostgreSQL constraints, repeatable concurrency tests and genuine process recovery
  together; a passing test count alone is not a universal proof.
- **What was found during hardening?** A real lock-timeout translation gap, a
  populated-migration backfill blocked by immutability, and dynamic broker port
  changes on restart. Each received a fix and regression evidence.
- **What would you change at larger scale?** Measure statement-window and
  reconciliation costs, then design checked checkpoints, bounded scans and archival
  preserving immutable history and permanent key uniqueness.
- **Which production assumptions remain?** Trusted host administrators, TLS/secret
  operations, off-host backup/PITR policy and multi-node failover are deployment
  responsibilities; the project remains simulated infrastructure.

## Further learning exercises

Give the first-minute explanation without framework names. Ask a peer to choose
any crash boundary and identify surviving state plus the next safe action. Trace
one invariant from Java to SQL to a test and then to an operational signal.
Use the expanded interview workbook to challenge the explanation, and reproduce
one measured workload without changing its run IDs into accidental replays.
