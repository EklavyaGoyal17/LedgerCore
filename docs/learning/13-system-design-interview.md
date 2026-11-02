# System design interview: defend the boundary

## Concept and purpose

Start with invariants and failure semantics, then explain needed components.
Accounts CRUD does not explain concurrent spending, duplicates, audit, or unknown
outcomes. LedgerCore is a simulated modular monolith that makes these inspectable.

## A ten-minute repository-specific explanation

1. Model exact INR/USD minor units, customer liabilities, settlement offsets,
   immutable journals, and the absence of real payment rails.
2. Trace actor/DTO, canonical reservation, ordered locks, funds/currency checks,
   journal/projection/outcome, replay response, audit/outbox, commit, response.
3. Race two 800 withdrawals from 1000 to separate recipients. Identify the
   source lock and why the loser sees 200.
4. Lose the response after commit: replay returns the original outcome. A new
   key means a new request.
5. Stop Kafka: event intent remains durable. Explain ack-loss duplicates,
   consumer marker/projection atomicity, quarantine, and operator replay.
6. Reconcile postings, projections, transfers, currencies, reversals, retained
   responses, and expected evidence using one coherent snapshot.
7. Discuss measured limits: hot accounts, full-history checks, windows, pool
   competition, and the single-node local deployment.

Read `docs/architecture/design.md`, `financial-integrity.md`, ADRs, and tests.
`ArchitectureTest` protects module acyclicity and framework-free accounting
values. Future extraction still requires explicit distributed ownership; a
modular monolith is not already a distributed transaction protocol.

## Trade-offs to defend

| Decision | Benefit | Cost or next question |
|---|---|---|
| PostgreSQL local commit | atomic financial/evidence boundary | database operation and recovery |
| Ordered pessimistic locks | explicit concurrent funds decision | hot-account waiting |
| Checked projection and journal | fast locked decision plus derivation | history aggregation/write checks |
| Durable idempotency | crash-safe duplicate arbitration | indefinite retained key/outcome |
| Outbox and deduplication | intent survives broker failure | duplicates, retry order, connection occupancy |
| Redis admission only | outages cannot erase money | fail-open/closed availability policy |
| Separate DB roles | runtime cannot disable invariants | provisioning and migration credentials |

## Extend the problem carefully

A future daily limit across accounts introduces a new shared predicate. Existing
account locks may not cover it: define a guard row or serializable protocol and
retries. Real external rails need pending/settlement states and external
reconciliation; simulated funding is not that integration. Multi-region writes
need ownership and recovery-point objectives before any active-active claim.

## Questions and failure discussion

- **Why not microservices now?** The cohesive financial commit has no demonstrated
  extraction need that justifies adding distributed coordination.
- **Does Kafka exactly once replace HTTP idempotency?** It cannot arbitrate two
  HTTP postings before outbox insertion or atomically mark that database row.
- **How to scale a hot account?** Independent-account partitioning helps other
  work; one spending invariant still needs serialization or bounded reservations.
- **What is verified?** Name actual reports and PostgreSQL/Kafka/Redis/process,
  populated-upgrade, and restore experiments. Test source alone is insufficient.

Avoid Redis-lease proposals without expiry analysis, repeatable-read claims that
ignore write skew, and error-rate charts mixing rejection with technical failure.
Runtime privilege, backup/restore, and poison-event tooling now exist. TLS,
managed key rotation, offsite backup policy, multi-node failover, and archival
remain operational or extension decisions.

## Exercise

Explain the first minute without framework names. Let a reviewer choose any
crash boundary and identify the surviving fact and next safe action. End with
one actual measured observation and one limit it cannot establish. Challenge
your explanation using `interview-questions.md` rather than memorizing a diagram.
