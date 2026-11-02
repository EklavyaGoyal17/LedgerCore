# Measured local workload experiment

Executed **2 November 2026** against packaged source revision `a657c40`, Java 21,
PostgreSQL 17.6, Redis 7.4.5 and Kafka 3.9.1. The Windows 11 host has 16 logical CPUs;
Docker Desktop 29.5.3 reports 16 CPUs and 8,151,265,280 bytes of memory, WSL2 and
overlayfs storage. These are local shared-workstation measurements, not production
capacity guarantees. No heavy native build/test ran during the workload interval.

The runner used k6 1.0.0, five sequential 60-second modes, five virtual users
normally and 20 for contention, with 0.1-second think time. One funded INR account
pair per normal VU avoids unrelated-account contention. All contention VUs share
the first pair. Every financial request has a unique key. The local request limit
was temporarily 100,000/minute; ordinary 120/minute operation was restored afterward.
The token fixtures were deleted and no tokens/passwords are included here.

| Mode | Requests | Requests/sec | p50 ms | p95 ms | p99 ms | Completed postings |
|---|---:|---:|---:|---:|---:|---:|
| Account reads | 2,758 | 45.96 | 6.26 | 12.45 | 16.88 | 0 |
| Balance reads | 2,824 | 47.04 | 4.75 | 8.91 | 11.52 | 0 |
| Transfer creation | 2,506 | 41.72 | 15.78 | 33.29 | 47.10 | 2,506 |
| Shared-source contention | 5,536 | 91.94 | 39.14 | 498.00 | 767.73 | 5,536 |
| Mixed reads/writes | 2,659 | 44.24 | 6.56 | 28.48 | 47.61 | 887 |

All **16,283 requests** passed their checks, with zero HTTP failures and zero
insufficient-funds results. The financially successful write count was 8,929.
Reconciliation was **CLEAN before and after** the experiment, checking 27,036
records at completion. The database grew from 66 to 8,995 journals and from 132 to
17,990 entries; fixture deposits are included in the starting dataset. Dataset
growth and differing VU counts mean the modes are not controlled capacity comparisons.

## What the run reveals

The shared-account workload has a substantially higher tail latency. This is
consistent with serializing funds decisions under account-row locks, while twenty
callers compete for sixteen pool connections. Database lock-wait durations were
not directly measured, so that explanation is an inference from the implementation
and workload, rather than an attributed lock-wait measurement.

Prometheus samples every five seconds. During the experiment its maximum observed
active pool count was 16, pending callers 0, pending outbox 5,602 and Kafka consumer
lag 0. These sampled values can miss short bursts. In particular, a zero sampled
Kafka lag does not establish end-to-end event completion: unpublished PostgreSQL
outbox rows have not reached the broker. See [sampled metrics](prometheus.json).
Resource snapshots in experiment.json were taken **after each mode**, not at peak
load; they must not be interpreted as continuous CPU/memory maxima.

The publisher's default 25-row batches with a one-second delay intentionally bound
scheduled work and broker-failure exposure. In this run it could not keep pace
with write bursts: pending publication was 1,231 after transfers, 5,642 after
contention, and 5,455 at the final reconciliation snapshot. These are durable
committed events. The separate [delivery observation](../event-drain.json) records
their eventual publication and durable consumer projection with no quarantine.
Its elapsed time includes backend recreation to restore the normal limit and
operational checks; it is not an isolated publisher-throughput benchmark.

For higher sustained event throughput, measure a larger configured batch or a
bounded lease/parallel publisher, then repeat acknowledgment-loss, duplicate,
outage and pool-contention tests. Preserve durable identity and failure behavior.
The current run demonstrates correctness and recovery under this workload; it
does not justify an unbounded queue or a production latency SLO.

The [actual statement query plan](../../architecture/query-plan-evidence.txt) was
captured afterward on 17,990 entries. The busiest ledger had 6,212 entries; the
window query sorted by posting timestamp/entry ID and ran in 10.698 ms in that
capture. The planner chose sequential scans/hash join for this distribution.
Opening-balance checkpoints and archival need measured justification before
optimizing away authoritative history scans.

## Reproduce and inspect

```sh
python scripts/load-experiment.py --base-url http://localhost:8088 \
  --duration 60s --vus 5 --contention-vus 20
```

Use the temporary rate-limit configuration described in `load-tests/README.md`,
then restore the normal limit. Run only against simulated isolated fixtures.
[summary.json](summary.json) contains the comparison above; experiment.json records
the exact timestamps, fixture shape, database counts, reconciliation and Docker
allocation. The five mode JSON files retain k6 latency/counter/check summaries.
Results reflect this machine, history size, sampling and configuration only.
