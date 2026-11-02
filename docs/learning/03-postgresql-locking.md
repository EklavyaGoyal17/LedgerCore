# PostgreSQL locking, retries, and deadlocks

## Concept and purpose

A row lock makes conflicting operations wait for an existing transaction to
finish. It turns a funds decision into one serialized decision per participating
account. A process-local `synchronized` block would protect only one JVM, and
ordinary read committed queries alone would not protect the read/check/write gap.

## The exact LedgerCore protocol

`AccountStore.lock` removes duplicate IDs, sorts UUIDs using Java's consistent
comparison, and issues one `SELECT ... FOR UPDATE` per row. Every writer uses that
protocol for both participants. The returned projection is validated only after
its row has been locked. Java UUID order differs from PostgreSQL UUID order for
some values; that is harmless because separate statements explicitly determine
acquisition order and every application writer uses the same Java comparison.

| Step | Request X: send 800 to B | Request Y: send 800 to C |
|---|---|---|
| 1 | reserves key X | reserves different key Y |
| 2 | locks shared source containing 1000 | waits on that source |
| 3 | checks funds, posts journal, projection becomes 200 | cannot make its funds decision yet |
| 4 | COMMIT releases row lock | acquires row and reads 200 |
| 5 | returns completed outcome | commits insufficient-funds outcome without a journal |

`LedgerCoreIT.repeatedConcurrentWithdrawalsCannotOverspend` repeats this race ten
times with distinct recipients. The opposite-direction test ensures A-to-B and
B-to-A use the same acquisition order. Customer balance checks and journal
projection constraints are additional defenses, not substitutes for the protocol.

## A real deadlock rather than a mocked exception

`FinancialIntegrityIT.realPostgresDeadlockVictimRetriesWithoutDuplicatePosting`
uses an external connection that deliberately violates the application order:

| Step | Transfer engine | External fixture |
|---|---|---|
| 1 | not started | locks second account |
| 2 | locks first account, waits for second | holds second account |
| 3 | waiting | requests first account and waits |
| 4 | detects cycle; PostgreSQL aborts engine attempt | can now acquire first account |
| 5 | starts new transaction | commits and releases both locks |
| 6 | locks both, posts once, commits | finished |

The fixture gives its external transaction a longer deadlock detection interval
and observes `pg_stat_activity` before forming the cycle. It checks a real retry
metric and final accounting state. This demonstrates recovery from another SQL
path violating the order; it does not imply that the application intentionally
creates deadlocks. PostgreSQL may choose a victim; production code cannot assume
which business operation it will be. See [PostgreSQL explicit locking](https://www.postgresql.org/docs/17/explicit-locking.html).

## The timeout bug the experiment found

The separate timeout test holds a row beyond `SET LOCAL lock_timeout='5s'`.
Spring's default exception translation did not reliably classify PostgreSQL
`55P03` as `PessimisticLockingFailureException`. Catching only that Java subtype
therefore missed a real contention failure. `TransferEngine` now examines the
cause chain for the narrow SQL states below, as well as the Spring locking type.

| SQL state | Meaning | Engine response |
|---|---|---|
| `55P03` | lock unavailable/lock timeout | retry whole transaction |
| `40P01` | detected deadlock | retry whole transaction |
| `40001` | serialization failure | retry whole transaction |
| `23514` and other integrity errors | invalid committed state | roll back; do not hide with concurrency retry |

There are at most three attempts: the initial attempt and two retries. Exhaustion
returns `503 CONCURRENCY_RETRY`, instructing the client to keep the original key.
Connection-pool acquisition timeout and SQL lock timeout are different symptoms;
this narrow retry policy does not silently treat every database outage as a
transient account lock.

## Optimistic alternative and mistakes

An optimistic version check would update only if `version=expectedVersion`, then
retry when no row matched. It must re-read funds, recreate the journal/outcome,
and commit the entire operation. At high contention, optimistic losers can spend
work constructing transactions that later abort. Pessimistic locking makes the
short critical section explicit. Neither approach automatically handles a future
multi-account aggregate limit: define and protect the whole invariant.

Avoid checking funds before acquiring the lock, inconsistent writer order,
network calls while holding customer locks, and arbitrary sleeps as a race-test
strategy. A lock can wait without forming a deadlock; only a cycle is a deadlock.

## Questions and practical exercise

- **Why lock the recipient?** Its projection can concurrently receive, spend, or
  reverse money; all writers need one shared protocol.
- **Does a normal statement query wait for `FOR UPDATE`?** Ordinary MVCC readers
  generally read committed versions; conflicting lockers/writers wait.
- **Why retry outside `TransactionTemplate`?** The victim transaction is aborted;
  its reservation and partial financial writes must be recreated together.

Run the two real contention tests with `mvn '-Dit.test=FinancialIntegrityIT' verify`.
Inspect `pg_stat_activity` and `pg_blocking_pids(pid)` while a disposable learning
transaction holds a row. Draw the wait-for graph before terminating anything.
