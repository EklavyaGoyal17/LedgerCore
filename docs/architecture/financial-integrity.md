# Financial consistency at the commit boundary

LedgerCore uses PostgreSQL as the authority for money movement. `TransferEngine`
locks the participating customer account rows, posts a balanced journal, updates
the balance projections, records the transfer and its audit/outbox event, and
finishes the durable replay record in one database transaction. A database error
rolls back the entire attempt. Kafka acknowledgments are outside this boundary.

The initial migration protects journal balance, journal immutability, projection
consistency, transfer posting shape, and completed reversals. The second migration
also binds **every financial outcome**, including an insufficient-funds rejection,
to one retained idempotency record. It backfills existing outcomes from their
stored response IDs before making that relationship mandatory and unique.

| Invariant | Database protection | Application responsibility |
|---|---|---|
| Balanced posted journal | Deferred journal checks; entries have one positive side | Exact minor-unit arithmetic and validated `Journal` |
| Correct projected balance | Deferred projection checks; nonnegative customer balance | Lock account rows before reading available funds |
| Consistent transfer and journal | Deferred posting shape/currency/reversal checks | Construct matching debit and credit entries |
| Durable replay | Unique principal/operation/key; transfer-to-replay foreign key; completed response/identity cannot change or be deleted | Canonical request fingerprint and replay response |
| Atomic audit and financial event | Deferred checks require the correct actor/action/event for each financial outcome | Insert both through `EventRecorder` before committing |
| No contradictory financial events | One financial audit and outbox event per outcome; deferred reverse references validate outcome/type | Consumers deduplicate deliveries of the same event ID |
| Immutable event evidence | Outbox envelope matches durable identity; content cannot change; financial events cannot be deleted | Publisher changes only acknowledgment/retry bookkeeping |
| Currency and authority | Account currencies/status, source ownership, and administrator-only simulated deposits/reversals checked when inserting outcomes | Authenticate the user, validate JWT claims, and perform object authorization before posting |

Deferred checks are intentional: a journal is temporarily incomplete while its
entries and business outcome are being inserted. The transaction may reach the
commit boundary only when all related rows agree. A direct SQL insert using the
ordinary runtime role is subject to the same checks. A schema owner or superuser
can disable triggers; isolated test fixtures use that privilege deliberately to
prove that reconciliation independently detects damaged state. Production runtime
credentials must never own the schema or have superuser privileges.

`expires_at` is retention metadata. Changing it does not release the unique key,
replace the fingerprint, or change the stored outcome. Financial idempotency,
audit, journal, and outbox evidence is retained indefinitely in this implementation.
Archival must preserve the same replay guarantee and complete evidential chain;
deleting expired keys or published financial outbox rows is not a safe cleanup job.
This favors correctness at the cost of unbounded retained history until an archival
design is implemented.

## Actual contention and recovery tests

`FinancialIntegrityIT` complements `LedgerCoreIT` with PostgreSQL experiments:

1. An independent connection holds an account row lock. A transfer exceeds
   PostgreSQL's five-second `lock_timeout`, rolls back its attempt, and retries the
   entire idempotent operation. The fixture observes the retry metric before
   releasing the lock; it does not use a sleep to guess when contention occurred.
2. An external transaction deliberately acquires the second account first, while
   the engine locks the first account and waits for the second. Once PostgreSQL
   reports the engine waiting on a lock, the external transaction requests the
   first account. This creates a real database deadlock. The engine's shorter
   deadlock detection interval selects it as the victim; it retries successfully
   after the external transaction releases its locks. The final journal and
   balances prove that the aborted attempt did not post twice.
3. Six concurrent reversal requests with different keys produce one compensation;
   the other callers receive `ALREADY_REVERSED`. Six requests with the same key
   replay the same compensation instead. Conflicting payloads with one key produce
   one durable outcome and one `IDEMPOTENCY_CONFLICT`.
4. Generated operation sequences use four repeatable random seeds and both INR and
   USD. Every operation replays its key, some successful transfers are compensated,
   balances stay nonnegative, customer liabilities remain constant, and the
   authoritative reconciliation report remains clean.

The overflow fixture places a destination projection at the `bigint` limit using
test-owner privileges. The application returns a durable
`BALANCE_LIMIT_EXCEEDED` rejection before creating any journal or altering either
balance. This fixture exercises the numeric boundary without requiring millions
of maximum-sized deposits. It restores the damaged projection before checking
reconciliation.

Statement running balances use exactly the same `(posted_at, entry_id)` order as
the displayed rows and offset pages. A fixture with tied posting times and opposite
entry insertion times verifies that each displayed running balance equals the
sum of the displayed prefix. Offset pagination is still subject to changes
between separate requests; each individual statement request uses a repeatable
read snapshot.

Run the financial integration suites with Java 21 and Docker available:

```bash
mvn -B -ntp -Dit.test=LedgerCoreIT,FinancialIntegrityIT verify
```

The command also runs unit tests, static checks, packaging, and the selected
integration suites. The repository verification report records the latest executed
results; the presence of a test in source alone is not evidence that it passed.
