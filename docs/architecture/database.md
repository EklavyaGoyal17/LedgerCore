# Database design, isolation and query behavior

`V1__financial_core.sql` contains all schema and trigger definitions. Tables use UUID
primary keys, explicit FKs, supported-currency checks and database timestamps.
Monetary fields use bigint minor units; a single operation is capped at
1,000,000,000,000 minor units. PostgreSQL SUM(bigint) produces numeric. Aggregate
balance conversion to bigint fails rather than silently wrapping if an extreme
balance exceeds the representation; operational limits are needed before such scale.

## Concurrency timeline

| Time | Withdrawal A: 800 from 1,000 | Withdrawal B: 800 from 1,000 |
|---|---|---|
| 1 | reserves key A | reserves key B |
| 2 | locks source row | waits for source row |
| 3 | sees 1,000; posts journal and projection 200 | waiting |
| 4 | commits | acquires lock and reads updated 200 |
| 5 | returns 201 | commits FAILED result, no journal; returns 422 |

READ COMMITTED alone allows two independent reads of 1,000. `SELECT FOR UPDATE`
forces the second transfer to read the winning transaction's updated projection.
Every participating customer account is locked, even the recipient, preventing
transfer/reversal races. Java UUID order is deterministic across all writers even
though its signed comparison differs from PostgreSQL UUID ordering; individual
lock statements make that distinction irrelevant. Opposite-direction transfers
acquire the same first row, avoiding the classic A-then-B / B-then-A cycle.

Lost update overwrites another writer's result; nonrepeatable read sees a changed
row on reread; phantom read sees a changed result set; write skew updates different
rows after reading a shared invariant. Row locks here protect the specific account
invariant; a future aggregate credit-limit rule spanning rows needs its own locking
or SERIALIZABLE design. REPEATABLE READ supplies one stable reconciliation snapshot
but is not a universal replacement for locking. Optimistic versions would reject
conflicting updates and require retries; high-contention money movement favors
short pessimistic critical sections. Deadlock victims and lock timeouts require
whole-transaction retry. Bounded attempts avoid permanent retry storms.

## Index map

| Index | Query |
|---|---|
| `financial_accounts_owner(owner_id,created_at,id)` | owner account list, ordered pagination |
| `journal_entries_journal(journal_id)` | journal balance checks and journal lookup |
| `journal_entries_statement(ledger_account_id,created_at,id)` | account ledger history, running balance window |
| unique `(principal_id,operation,key)` | durable request reservation/replay |
| unique partial `one_completed_reversal` | one compensating reversal per original |
| transfer source/destination indexes | participant history / investigation |
| partial `outbox_pending(next_attempt_at,created_at)` | due pending events without indexing published history |
| `audit_events_resource(resource_id,created_at)` | trace an affected financial resource |

Primary and unique keys supply their own indexes. Indexes consume storage and
amplify inserts; no index is added for hypothetical filters. Financial queries use
explicit SQL, so there is no lazy entity traversal producing N+1 reads. Identity
uses a single JPA repository lookup. Statement window queries scan prior entries
to derive a running balance before pagination; offset pagination is bounded but
cost grows with ledger history. Future opening-balance checkpoints and cursor
pagination need measured justification and invariant tests.

Use `scripts/query-plans.sql` after generating a representative dataset. Save
EXPLAIN (ANALYZE, BUFFERS) output and compare estimated/actual rows, sorts, buffer
reads and execution time. A sequential scan on a tiny table is often correct.
Do not force an index based on an empty demo database. Hikari limits active
connections to 16 with a five-second acquisition timeout; lock contention,
publisher waits and full reconciliation scans compete for those connections.

Reconciliation currently scans full history in memory and is intended for
educational datasets. An operational implementation needs bounded report sizes,
chunked snapshots, archival and carefully managed transaction duration. No
automatic corruption repair is provided.
