# Performance engineering with an accounting boundary

## Concept and purpose

Performance is a measured relationship between workload, data size, contention,
and resources. A correct hot-account spending decision intentionally serializes;
more threads cannot remove its invariant. Separate request throughput, new
financial postings, business rejections, and technical failures.

## What the current code costs

`AccountStore.lock` defines the critical section. Hikari caps connections at 16;
the publisher also holds one while awaiting Kafka. `AccountService.balance`
derives amounts from entries. Statement running balances need preceding history
before LIMIT/OFFSET. Deferred projection checks aggregate authoritative entries
when posting. These are deliberate correctness costs whose behavior changes
with ledger size.

`load-tests/workload.js` provides accounts, balance, transfers, contention, and
mixed modes. Mixed mode performs two balance reads per transfer iteration. Normal
writes select per-VU fixtures; contention shares one source. RUN_ID plus VU and
iteration forms fresh keys so a write benchmark does not accidentally measure
replay. Completed postings and insufficient-funds rejections have separate
counters. The script accepts documented 422 business outcomes and applies
check/technical-failure thresholds.

`scripts/load-experiment.py` provisions simulation accounts, keeps bearer tokens
in temporary mounted fixtures, records host/Docker/configuration details, saves
summaries, and runs initial/final reconciliation. Its default modes each run 60
seconds. Evidence is limited to the actual duration/dataset. Raise admission
limits explicitly for an isolated local experiment so rate limiting is not
confused with posting capacity. Never commit tokens or print admin secrets.

## Reproducible experiment design

| Dimension | Record | Reason |
|---|---|---|
| Runtime | commit, JVM, dependency versions, images | compare the same program |
| Host | CPU, Docker memory, OS, competing work | resource limits alter results |
| Dataset | accounts, entries/account, funds | aggregation cost grows with history |
| Workload | mode, VUs, duration, think time, fixture sharing | contention differs from independent writers |
| Outcomes | new 201, business 422, technical errors | fast rejection is not posting capacity |
| Latency | p50/p95/p99 and full samples | averages hide long waits |
| Correctness | before/after reconciliation | speed cannot excuse damaged accounting |

Compare five independent sources with twenty callers sharing one source. If pool
waiting rises while that source's posting rate stays flat, serialization is a
plausible bottleneck. Confirm with blockers/timing before adding connections.
This is a diagnostic example, not a measured result.

## Plans and bounded history

Run `scripts/query-plans.sql` on seeded data and retain actual EXPLAIN ANALYZE
BUFFERS output. Distinguish account-entry lookup from statement ordering: the
`(ledger_account_id,created_at,id)` index helps find history, but statements now
order by `(journal.posted_at,entry.id)` for consistent running balances. A sort
can remain; do not claim that this index covers a cross-table order.

A tiny table can correctly use a sequential scan. Compare estimated/actual rows,
buffers, sort behavior, and time on representative data. Full reconciliation and
deep history need an eventual bounded design. Checked opening-balance checkpoints
and cursor pagination are possible extensions requiring their own invariants;
they are not implemented optimizations.

## Questions and exercises

- **Why not set pool size to 200?** More transactions can deepen contention
  without increasing the serialized account rate.
- **Why can 422 make the benchmark faster?** It avoids posting work; count it
  separately from successful transfers.
- **Why a unique run ID?** Reused keys can turn a write experiment into replay.
- **Does a green threshold prove production capacity?** Only the measured
  workload, duration, dataset, and infrastructure were exercised.

Read `load-tests/README.md` for current commands/results. Save actual summaries,
compare independent and contended fixtures, and propose one optimization based
on the observed bottleneck. Do not invent percentile numbers or call a short
smoke run a sustained production benchmark.
