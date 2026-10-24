# Architecture and invariant map

LedgerCore is a modular monolith. Package boundaries group identities, accounts,
ledger, transfers, reconciliation and events. Controllers validate wire shapes and
map authenticated JWTs to `Actor`; application services control authorization and
transactions; domain records validate exact amounts, journal shape and transitions;
JPA/JDBC adapters persist state. There is one deployable process and one database.

`TransferEngine` is the financial transaction coordinator. It owns a
`TransactionTemplate` using READ COMMITTED, a 20-second transaction timeout and
5-second PostgreSQL lock timeout. Retries start a **new complete transaction**;
the engine never retries an individual SQL statement after an aborted transaction.

```mermaid
sequenceDiagram
  participant C as Client
  participant E as TransferEngine
  participant DB as PostgreSQL
  participant K as Kafka
  C->>E: JWT + key + transfer
  E->>DB: INSERT idempotency ON CONFLICT DO NOTHING
  alt Existing key
    DB-->>E: committed fingerprint and response
    E-->>C: original response or 409
  else New key
    E->>DB: lock accounts in sorted UUID order
    E->>DB: journal + projections + transfer + audit + outbox
    E->>DB: store replayable outcome
    E->>DB: COMMIT (deferred checks)
    E-->>C: 201 or durable 422
    Note over E,C: response can be lost; key safely recovers
    E->>K: publisher sends after financial commit
  end
```

| Invariant | Application enforcement | Database enforcement |
|---|---|---|
| Balanced journal | `Journal` sums exact integers | deferred `verify_journal` trigger requires ≥2 entries and equal sums |
| Atomic movement | one `TransferEngine` transaction | PostgreSQL commit/rollback; completed transfer must match two exact entries |
| Nonnegative available balance | lock before check | row-locked projection update, nonnegative CHECK and deferred derived projection equality |
| Durable idempotency | canonical minor units + SHA-256 | unique principal/operation/key; unfinished record cannot commit |
| Immutable posted entries | no mutation operations | UPDATE/DELETE guards; append only in original creating transaction |
| Currency consistency | `Money.Currency`, account comparisons | account/journal mapping checks and entry currency guard |
| Auditability | `EventRecorder` in each financial operation | append-only audit rows with actor FK and database timestamps |
| Event consistency | `EventRecorder` in financial transaction | local atomic commit of journal/result/outbox; reconciliation flags missing records |

The journal is authoritative. `financial_accounts.balance_minor` is a transactionally
maintained projection used for efficient locking and overdraft decisions. Public
balance queries aggregate entries instead of trusting a cache. The deferred
projection check reads the current database state at commit, including the
transaction's own inserts. Row locks serialize all engine writers to each account.

Liabilities are credit-normal: credit increases customer funds, debit decreases
them. The settlement asset is debit-normal. Depositing 1,000.00 debits settlement
100,000 minor units and credits customer liability 100,000. Moving 500.00 debits
source liability 50,000 and credits recipient liability 50,000. Internal movement
preserves the sum of customer liabilities. A reversal adds the opposite entries,
retaining the original forever.

Transfer lifecycle CREATED → PROCESSING → COMPLETED/FAILED occurs inside one
transaction using `TransferLifecycle`. Only terminal states become visible.
Technical failures roll back the entire attempted operation; insufficient funds
commits a FAILED record with no journal. Thus there is no persistent job in
PROCESSING that requires an ambiguous recovery worker.

Idempotency records never expire operationally. `expires_at` marks a minimum
seven-day retention horizon, **not permission to reuse a financial key**. Keys and
responses remain retained indefinitely until a separately designed archival policy
preserves permanent uniqueness. Tests move the horizon into the past and verify
that replay still works. Replay preserves status and JSON values; PostgreSQL JSONB
may reorder properties and whitespace. New request correlation headers can differ.

All ordinary financial entry points use the same locking protocol. Database owners
can disable triggers; production must separate the migration owner from a restricted
runtime role. These constraints defend ordinary application DML, not a malicious
superuser or physical storage corruption. Reconciliation is an independent detective
control; it never repairs money automatically.

Outbox workers hold one row lock while waiting (bounded) for broker acknowledgment.
`SKIP LOCKED` allows multiple workers. This simple implementation prioritizes
recoverability over maximum publication throughput. A future lease design can reduce
connection occupancy but must handle lease expiry and duplicate sends. Publication
and consumer effects are at least once, with durable deduplication. Ordering is not
guaranteed across retries, even with Kafka aggregate keys. Audit projection is
append-only by event identity and does not rely on ordering.
