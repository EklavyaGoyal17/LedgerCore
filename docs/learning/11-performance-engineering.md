# Performance engineering

## Concept in plain English

Performance depends on workload, contention, dataset size and resource limits; a measured bottleneck matters more than a technology label.

## Why backend engineers need it

Financial locking is intentionally serialized per hot account, so increasing threads cannot remove that limit.

## LedgerCore implementation

Hikari caps the pool at 16. Statements have bounded offset pagination and indexed ledger access. k6 supports reads, balances, transfers, contention and mixed workloads; query-plans.sql captures actual plans.

## Relevant files and classes

`load-tests/workload.js`, `load-tests/README.md`, `scripts/query-plans.sql`, `docs/architecture/database.md`.

## Concrete example

Compare 1, 5 and 20 virtual users sharing one source. Each transfer locks that source. Rising pool wait with fixed posting throughput suggests contention rather than missing CPU.

## Common mistakes

Fabricating benchmark numbers, comparing different datasets, increasing pool size without database capacity, or benchmarking only replays leads to false conclusions.

## Failure scenario

A statement window scans prior entries; deep history can grow expensive despite LIMIT. A long reconciliation snapshot retains resources. Outbox network waits consume connections.

## Interview question and answer

Why can an index be unused? Small tables may be cheaper to scan; poor selectivity or stale statistics can also matter. Read EXPLAIN ANALYZE before forcing a plan.

## Further learning exercise

Record p95/p99, CPU, connection usage, waits and dataset size for each mode. Design running-balance checkpoints without making them the accounting authority.
