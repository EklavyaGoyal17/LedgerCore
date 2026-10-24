# Production debugging

## Concept in plain English

Debugging starts with a precise symptom and a timeline of durable facts; changing data before understanding the failure can destroy evidence.

## Why backend engineers need it

A financial incident may involve an unknown response, delayed event or real inconsistency, each requiring different recovery.

## LedgerCore implementation

Correlation IDs connect HTTP logs; transfer IDs link journal/audit/outbox; reconciliation persists affected references. Failed business outcomes are distinguishable from transport failures.

## Relevant files and classes

`common/ProblemAdvice.java`, `reconciliation/ReconciliationService.java`, `docs/failure-scenarios/recovery.md`, `scripts/query-plans.sql`.

## Concrete example

A client reports timeout. Replay the original key or query its transfer ID. If COMPLETED exists, avoid a new payment; investigate response delivery. If outbox is pending, repair publication separately.

## Common mistakes

Deleting an outbox backlog, editing old journal rows, assuming HTTP 500 proves rollback, or restarting every service immediately can worsen the incident.

## Failure scenario

Reconciliation detects but never silently repairs corrupted projections. Integration fixtures disable a trigger only inside a test database and restore it; no production endpoint offers this operation.

## Interview question and answer

What do you check for a slow transfer? Pool acquisition, pg_blocking_pids, transaction age, database query plan, Redis timeout, JVM/CPU and dependency state.

## Further learning exercise

Write a simulated incident report using a broker outage. Identify detection, affected guarantee, evidence, mitigation and a follow-up test without exposing tokens.
