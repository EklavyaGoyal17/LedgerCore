# ADR 001: PostgreSQL owns financial truth

Status: accepted.

## Context

Money movement needs atomic multi-row updates, constraints, durable replay and real row locks.

## Decision

Use PostgreSQL and Flyway. The migration defines deferred journal/projection checks; TransferEngine owns one local transaction.

## Alternatives

A document store would need additional transaction and invariant mechanisms. H2 is convenient for simple tests but cannot establish PostgreSQL concurrency behavior.

## Consequences and verification

Database availability is required for posting. Schema migrations and backups become critical. Testcontainers exercises the actual database engine.

Read the corresponding implementation and tests before changing this decision. `docs/VERIFICATION.md` records actual execution evidence.
