# System design interview

## Concept in plain English

A strong system design identifies invariants, transaction boundaries, failure semantics and explicit scale assumptions before choosing components.

## Why backend engineers need it

Financial correctness questions expose gaps that a generic CRUD diagram conceals.

## LedgerCore implementation

LedgerCore chooses a modular monolith, relational local commit, row locks, immutable journals, database idempotency and an outbox. Reconciliation provides independent detection.

## Relevant files and classes

`docs/architecture/design.md`, `docs/adr`, `TransferEngine`, `Idempotency`, `OutboxPublisher`, `LedgerCoreIT`.

## Concrete example

Explain a 1000 balance with two 800 requests, then add response loss and broker outage. Show where the lock, stored outcome and outbox each solve a different failure.

## Common mistakes

Claiming Kafka exactly once solves PostgreSQL coordination, proposing Redis locks without lease analysis, or splitting the ledger into services before defining ownership weakens the design.

## Failure scenario

Single-node Compose is not high availability. Region failover, backups, secret rotation, runtime least privilege and archival are explicitly future operational work.

## Interview question and answer

How would you scale hot accounts? Partition by account ownership can help unrelated accounts, but a single account's spending invariant still needs serialization or a carefully bounded reservation model.

## Further learning exercise

Give a ten-minute design walkthrough with an invariant table, sequence diagram, failure matrix and one measured performance observation. Use interview-questions.md to challenge each claim.
