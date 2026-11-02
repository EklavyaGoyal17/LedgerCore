# LedgerCore interview workbook

Read each answer with the referenced class open. These questions describe the
implemented system, not a hypothetical bank deployment.

1. **What is authoritative for a customer balance?** Posted journal entries.
   `AccountService.balance` aggregates credits minus debits. The locked projection
   is a checked acceleration structure, never Redis truth.

2. **Why is a customer account a liability?** The simulated platform owes those
   funds to the customer. Its normal balance increases with credits, so a source
   transfer debits that liability.

3. **What offsets a simulated deposit?** A debit-normal settlement asset in the
   same currency. It balances the credit to the customer's liability account.

4. **Why represent amounts as bigint minor units?** Exact integer addition avoids
   floating-point drift. `Money.parse` enforces supported currencies, precision,
   positivity and a per-operation upper bound before posting.

5. **Where is journal balance enforced?** `Journal` checks in memory, and deferred
   PostgreSQL triggers require at least two entries and equal debit/credit sums
   at commit. Application validation alone is insufficient defense.

6. **Can a balanced journal still be wrong?** Yes: it can use a wrong participant,
   amount or currency. `verify_transfer` and reconciliation compare the exact
   completed transfer shape to its journal.

7. **Why defer checks to commit?** Intermediate inserts are temporarily incomplete
   while a journal is assembled. Other transactions cannot see those uncommitted
   rows; deferred checks reject an invalid final transaction.

8. **How are committed entries immutable?** Trigger guards reject UPDATE/DELETE.
   Entry insertion verifies that its journal was created by the current database
   transaction, preventing later appends to committed history.

9. **How do you correct a transfer?** Add a new compensating journal referencing
   the original. `TransferEngine.reverse` requires admin authorization, a completed
   ordinary transfer, one successful reversal and sufficient recipient funds.

10. **Why cannot reversal force an overdraft?** It applies the same locked funds
    check in the opposite direction. A failed reversal is durable; after funding,
    a new key is required for a new attempt.

11. **Why is READ COMMITTED alone unsafe?** Two sessions may both read the same
    spendable balance before either updates it. Their independent validations
    do not serialize the combined spending decision.

12. **Which lock prevents two ₹800 withdrawals from ₹1,000?** Both must acquire
    the source `financial_accounts` row lock before reading its projection.
    The loser sees 200 after the winner commits and posts no journal.

13. **Why lock recipients too?** Incoming transfers, outgoing transfers and
    reversals modify their projections. One shared protocol keeps these
    operations serialized per participating account.

14. **How do opposite-direction transfers avoid the classic deadlock?**
    `AccountStore.lock` sorts UUIDs and locks one row per statement in that order,
    so both directions compete for the same first account.

15. **Why not rely on ORDER BY in a multi-row lock query?** Individual statements
    make acquisition order explicit and independent of query planner behavior.
    Java's UUID order need only be consistent across all writers.

16. **What retries are safe?** The engine retries lock-acquisition failures at most
    twice around a new complete transaction. Retrying only one statement in an
    aborted PostgreSQL transaction is not a recovery strategy.

17. **What does optimistic locking change?** A version condition rejects stale
    writes rather than waiting for the row first. It can be correct with complete
    retries, but contention makes retry costs significant for hot accounts.

18. **What is write skew?** Transactions read a shared invariant but update
    different rows, allowing both to commit a jointly invalid state. New
    aggregate limits need locks covering that invariant or SERIALIZABLE retries.

19. **Why use REPEATABLE READ for reconciliation?** All checks should inspect a
    coherent snapshot instead of mixing states from before and after unrelated
    concurrent commits across separate queries.

20. **How does a duplicate arriving before reservation commit behave?** PostgreSQL's
    unique insert conflict waits for the competing transaction. If it commits,
    the caller loads the result; if it aborts, a new insertion can proceed.

21. **What scopes a key?** Principal UUID, operation and key. Transfer and deposit
    namespaces differ; a reversal also scopes to its original transfer. Different
    principals can independently use the same textual key.

22. **Why hash canonical values?** Equivalent amounts such as 100.0 and 100.00
    become the same minor-unit request. A changed account, currency or amount
    with the same key must return 409 rather than posting again.

23. **What happens if the process dies with PROCESSING in memory?** Reservation
    and financial writes share one uncommitted database transaction. Rollback
    removes them; a deferred check also forbids committing unfinished records.

24. **Does a timeout imply a failed transfer?** No. `AFTER_COMMIT` tests the case
    where commit succeeded and the response vanished. Replaying the same key
    returns the original transfer with no new journal.

25. **Why retain insufficient-funds responses?** A retry of an old request must
    keep its original meaning even after funds arrive. A client intentionally
    trying again after funding should use a new key.

26. **What does key expiry mean here?** `expires_at` is an informational minimum
    seven-day retention horizon. Keys are not deleted/reusable; permanent
    uniqueness must survive any future archival design.

27. **Why cannot Kafka availability control transfer success?** Kafka is outside
    the local financial transaction. Financial writes atomically store an outbox
    event, allowing later publication without changing the committed money.

28. **Where can outbox duplication happen?** The broker may persist a message
    before the publisher marks its database row. A crash or lost acknowledgment
    causes replay of the same event ID.

29. **Does producer idempotence solve end-to-end exactly once?** No. It protects
    certain producer retries but does not atomically join Kafka persistence,
    the PostgreSQL outbox mark and consumer database effects.

30. **How is the consumer idempotent?** `EventConsumer` inserts a unique event ID
    and projection in one database transaction. Duplicates find the marker and
    return; a failure before commit rolls both back for redelivery.

31. **Why use SKIP LOCKED?** Multiple publisher workers can claim distinct due
    rows without waiting on one another. They still may redeliver after crashes
    and do not guarantee global or retry ordering.

32. **What is the outbox design's throughput trade-off?** A worker holds one
    database transaction/connection while awaiting broker acknowledgment. A
    lease design could reduce connection occupancy but introduces expiry races.

33. **Why is Redis not financial truth?** TTL, eviction and cache outages should
    affect admission/performance, not lose committed money or duplicate keys.
    LedgerCore uses Redis only for shared fixed-window rate counters.

34. **Why put increment and expiry in Lua?** Separate commands can crash between
    INCR and EXPIRE, leaving a permanent limit counter. The script executes both
    atomically on Redis.

35. **What is the Redis outage policy?** Authentication fails closed to retain
    brute-force protection; existing authenticated business requests fail open.
    PostgreSQL constraints still enforce financial correctness.

36. **Why check ownership after JWT validation?** A valid identity is not
    permission to every account. `AccountStore.owned` denies private metadata,
    balances and statements belonging to another customer.

37. **Why validate issuer and audience?** A valid signature from a trusted key
    does not by itself prove the token was issued for this service. The decoder
    also verifies expiry and current database identity role/status.

38. **Why constrain password bytes?** BCrypt only consumes a bounded byte input.
    Printable ASCII plus a 72-character maximum avoids accidental truncation of
    multibyte or overly long passwords in this explicit educational policy.

39. **Why are logs not an audit ledger?** Logs can be sampled, rotated or lost
    independently. Durable `audit_events` commit with financial changes; logs
    help diagnose them using safe IDs and categories.

40. **How does reconciliation differ from enforcement?** Constraints and locks
    prevent ordinary invalid commits. Reconciliation independently detects
    mismatches, orphan journals, invalid reversals and missing audit/events;
    it reports affected references and never silently repairs money.

41. **What proves concurrency here?** Real PostgreSQL Testcontainers races repeat
    the two-withdrawal scenario ten times and test opposite directions. Generated
    sequences check liabilities, nonnegative projections and clean reconciliation.
    Tests support reasoning, not a universal proof for every deployment.

42. **How would you debug p99 latency?** Compare HTTP duration, Hikari active/max,
    database blockers/transaction age, statement plans, Redis timeout, outbox
    network waits and JVM resources. Adding connections may worsen contention.

43. **What does the running-balance statement cost?** The SQL window needs prior
    entries before applying LIMIT. Deep offset pages and growing history need
    measured optimization, potentially checked checkpoints and cursor pagination.

44. **What remains before a production deployment?** TLS and ingress policy,
    managed secret/key rotation, encrypted off-host/PITR backups and multi-node
    failover, bounded reconciliation/archival and independent security review.
    Restricted runtime credentials, logical restore drills, abrupt JVM recovery
    and poison-event operations are implemented; they do not prove those wider
    deployment properties.

45. **What additional guarantees does V2 add?** Every completed or failed transfer
    references its immutable replay record and must have the matching financial
    audit and outbox evidence at commit. Contradictory or orphan financial
    evidence is rejected rather than merely found by a later check.

46. **What did populated-upgrade testing catch?** The legacy immutable transfer
    trigger rejected a privileged migration's new-column backfill. V2 now disables
    only that trigger around the backfill inside Flyway's transaction, then
    re-enables it. LegacyMigrationIT seeds real V1 history and verifies the upgrade.

47. **Why was catching only a Spring lock exception insufficient?** A real
    PostgreSQL55P03 timeout was not always translated to that subtype. The engine
    examines the SQL cause chain for55P03,40P01 and40001 and retries the entire
    transaction; unrelated database errors still propagate and roll back.

48. **How is abrupt process death different from a thrown exception?** Runtime.halt
    bypasses normal application cleanup. A fresh JVM must rely on PostgreSQL's
    committed facts. ProcessRecoveryIT tests both sides of commit and repeats
    replay from independent processes against the same database.

49. **Why isolate Flyway in a separate process?** Restricted datasource privileges
    provide little protection if the API process also retains the migration
    password. Compose's migrate job owns the schema; the backend receives no such
    credential, has Flyway disabled and cannot perform DDL or truncate history.

50. **Why does SELECT FOR UPDATE need a narrowly granted update privilege?**
    PostgreSQL requires UPDATE permission on at least one column. The runtime has
    UPDATE(id) for transfer locking, while the immutable trigger rejects actual
    edits, including no-op updates. The capability is tested under the runtime role.

51. **What happens to a poison Kafka record?** After bounded retries it is durably
    quarantined. If storing that evidence fails, recovery propagates the failure
    and Kafka cannot acknowledge it. Other records can progress after retention.

52. **Can manual event replay move money again?** It invokes the deduplicating
    audit consumer, not TransferEngine. Original evidence remains immutable;
    corrected payload and operator reason are audited. A successful projection,
    deduplication marker and quarantine resolution commit together.

53. **Why alert separately on outbox backlog and quarantine?** An event can be
    published successfully and then fail consumption, leaving zero outbox backlog
    but an unresolved quarantined effect. Broker lag can also fall after quarantine.

54. **What makes a trace verification meaningful?** The check supplies one sampled
    W3C context and verifies actual span ancestry across HTTP, transfer, journal,
    outbox, Kafka producer and consumer, plus durable delivery evidence. Unrelated
    traces in Tempo would not establish propagation across the outbox boundary.

55. **Does a passing dependency scan mean no known advisory exists?** No. The
    recorded runtime scan distinguishes patched findings from two expiring
    exceptions for unused Spring MVC features. Architecture rules prevent those
    affected APIs being introduced silently, and unresolved findings fail CI.

56. **How does the restore tool avoid overwriting live data?** It verifies the
    dump checksum before creating a new restricted-name database, refuses live
    or existing targets, restores in one transaction and checks ledger invariants.
    The drill removes only its own randomly named disposable target.

57. **How do generated properties differ from seeded integration sequences?**
    jqwik generates and shrinks domain counterexamples. PostgreSQL generated
    sequences exercise transaction boundaries and real invariants. Neither alone
    establishes all distributed failure behavior, so both complement crash tests.

58. **Why must statement sorting match its window ordering?** A running balance
    calculated in one order and displayed in another is misleading when timestamps
    tie. The same deterministic ordering is used for the window and visible rows.

59. **What happens before a destination bigint balance would overflow?** The
    engine records a durable BALANCE_LIMIT_EXCEEDED rejection with no financial
    journal. A retry of that key preserves the result rather than depending on a
    driver arithmetic error or silently wrapping a monetary amount.

60. **What makes the load experiment reproducible?** It records hardware/Docker
    allocation, duration, VUs, unique run IDs and raw percentile summaries. Normal
    modes use separate source pairs; contention intentionally shares one. Before
    and after reconciliation must be clean, and unexpected HTTP failures fail it.
