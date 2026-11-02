# Database transactions and isolation

## Concept and purpose

A database transaction makes a group of changes commit together or roll back
together. In this system, that group includes the journal, checked projections,
terminal transfer, idempotency response, audit, and outbox event. Returning a
successful HTTP response before that commit would describe an outcome the database
has not yet guaranteed.

ACID is a useful checklist: atomicity concerns all-or-nothing writes; consistency
concerns preserved rules; isolation concerns interactions with other transactions;
durability concerns committed data under the database and storage configuration.
A Java lock supplies neither PostgreSQL durability nor cross-process isolation.

## Actual transaction boundaries

Read `TransferEngine.execute`, `Idempotency.execute`, `JournalPosting`, and
`V1__financial_core.sql`/`V2__financial_integrity.sql`. The engine requests
`READ_COMMITTED`, sets a 20-second transaction timeout and a local five-second lock
timeout, then executes a bounded retry loop outside each transaction. Deferred
constraints inspect the completed state at commit; an incomplete journal may
exist privately while its entries are being assembled.

`ReconciliationService.run` and `AccountService.statement` use `REPEATABLE_READ`
so their multiple reads refer to one coherent snapshot. They are not financial
writers using a different funds-check protocol.

## Read committed timeline: nonrepeatable read and phantom

These are explanatory two-session experiments, not behavior the transfer path
relies on. Start with one funded customer account and no concurrent writers.

| Step | Session A: READ COMMITTED | Session B |
|---|---|---|
| 1 | BEGIN; read A's balance = 1000 | idle |
| 2 | waiting for next statement | post an authorized deposit of 100; COMMIT |
| 3 | read the same balance = 1100 | idle |
| 4 | count owner's accounts = 1 | create another account; COMMIT |
| 5 | repeat the count = 2; COMMIT | idle |

The changed balance is a nonrepeatable read; the extra matching account is a
phantom. PostgreSQL's read committed snapshots refresh between statements. Its
`REPEATABLE READ` prevents both in one transaction, including phantoms, although
the SQL standard permits weaker repeatable-read implementations. See the
[PostgreSQL isolation reference](https://www.postgresql.org/docs/17/transaction-iso.html).

## Stable snapshot is not a complete business invariant

Consider a proposed rule: among two payout channels, at least one must remain
available. Both start enabled. This is an illustrative future feature, not an
existing LedgerCore table.

| Step | T1 at REPEATABLE READ | T2 at REPEATABLE READ |
|---|---|---|
| 1 | sees both channels enabled | sees both channels enabled |
| 2 | disables channel A | disables channel B |
| 3 | commits its own row | commits its own different row |
| 4 | combined result: both disabled | shared rule violated |

That is write skew: no row was overwritten, and each snapshot was individually
consistent. A shared guard-row lock or an appropriate serializable transaction
with whole-operation retries is needed for this new invariant. Merely changing
LedgerCore's current isolation constant would not specify the missing rule.

At `SERIALIZABLE`, the database can detect a dangerous dependency cycle and abort
one transaction with `40001`. The application must retry all of its reads and
writes from a fresh snapshot. Serializable does not make retries or idempotency
unnecessary, and it does not join PostgreSQL to Kafka atomically.

## Commit is a real failure boundary

| Point | What another connection can observe | Safe client recovery |
|---|---|---|
| Before commit, including after one entry | no partial financial state | retry original key |
| Commit rejected by deferred constraint | no financial effect from that attempt | investigate the defect; do not retry constraint violations blindly |
| Commit succeeds, response disappears | complete transfer and replay record | repeat original key or query known transfer ID |

`LedgerCoreIT.precommitFailuresRollbackEveryFinancialEffect` exercises exceptions
inside this boundary. `ProcessRecoveryIT` adds abrupt child-JVM termination and
fresh-process replay; read its source to distinguish real termination from a
normal exception. Neither is a power-loss or replicated failover certification.

## Questions and experiments

- **Why not catch a SQL error and continue?** PostgreSQL has aborted that attempt;
  application code must roll back and create a fresh transaction where appropriate.
- **Does `readOnly=true` mean a durable business constraint?** It describes the
  transaction's intended use; permissions and database rules remain essential.
- **Can a commit fail after all inserts returned successfully?** Yes. Deferred
  invariants are checked at commit, which is why success is returned afterward.

Run `mvn '-Dit.test=LedgerCoreIT,FinancialIntegrityIT,ProcessRecoveryIT' verify` with
Docker available. In two local psql sessions, repeat a read under read committed
and repeatable read while a third-party API request commits a deposit. Roll back
both learning sessions afterward. Explain which result changed and why without
editing financial history directly.
