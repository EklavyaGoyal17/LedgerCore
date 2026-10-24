# Failure and recovery runbook

| Boundary | Durable effect | Recovery and test |
|---|---|---|
| Before transaction | none | retry original key; `BEFORE_TRANSACTION` |
| After validation | none after rollback | `AFTER_VALIDATION` |
| After one journal entry | none after rollback | `DURING_JOURNAL`; no visible partial posting |
| After outbox insertion | all writes roll back together | `AFTER_OUTBOX` |
| Before commit | all writes roll back together | `BEFORE_COMMIT` |
| After commit before response | complete transfer/result already durable | `AFTER_COMMIT`; replay key yields original result |
| Before Kafka acknowledgment | transfer unaffected; outbox pending | broker pause in `EventInfrastructureIT` |
| After Kafka ack before publication mark | duplicate may be sent | `AFTER_PUBLISH`; consumer deduplicates |
| During consumer transaction | marker and projection both roll back | `DURING_CONSUMER`; Kafka redelivery recreates one effect |
| After consumer DB commit before offset commit | duplicate delivery | unique processed ID makes redelivery a no-op |

`Faults` is a no-op production bean. Tests inject `TestFaults` with a one-shot
atomic fault boundary. There is no HTTP header, endpoint or environment switch for
production failure injection. These are deterministic exceptions modeling crash
boundaries, not proof of OS power-loss durability. PostgreSQL crash recovery and
storage durability depend on actual deployment configuration and tested backups.

## Demo A–D and G

`python scripts/demo.py` registers two users, creates accounts, deposits 1,500,
transfers 500, replays the same key, rejects a 2,000 withdrawal, races two 800
withdrawals against the remaining 1,000, inspects ledger statements and runs
reconciliation. Assertions fail on unexpected responses. Each invocation uses new
identities, accounts and keys.

## Demo E: lost response

Run `mvn -Dit.test=LedgerCoreIT#responseLossAfterCommitReplaysOriginalOutcome verify`.
The failure hook executes after TransactionTemplate returns (after commit), and
the second call proves one successful financial journal. No server-only failure
endpoint is necessary.

## Demo F: broker outage

```sh
docker compose stop kafka
python scripts/demo.py
docker compose start kafka
docker compose logs --tail=50 backend
```

Observe the pending-outbox panel rise then drain, while transfers and reconciliation
remain valid. Existing service processes are not restarted by dependency startup
conditions. Publisher retries grow exponentially from one second to at most 256
seconds in this implementation. Wait for the due time; never delete pending outbox
records to make a dashboard green. Broker restart with its named volume preserves
broker data. The integration test pauses/unpauses the real broker to retain its
advertised address and simulates acknowledgment loss; this is a narrower failure
than replacing a whole broker cluster.

## Demo H: load and debugging

Follow `load-tests/README.md`. Inspect HTTP latency, committed outcome rate, pool
utilization, outbox backlog and reconciliation. Traces are sampled and sent to
Tempo; set TRACE_SAMPLE_PROBABILITY=1 in a local experiment for full sampling.
Structured logs carry correlation/request IDs and duration. Never paste bearer
tokens or secrets into incident logs.

For a slow API, compare pool wait, database row lock wait, query plans, GC/CPU,
and Redis latency before changing pool sizes. `pg_stat_activity` shows blocked
backends; `pg_locks` and `pg_blocking_pids(pid)` identify blockers. Long publisher
network waits hold one connection, so prolonged Kafka outages can reduce capacity.

For discrepancies, preserve the run ID and affected references, limit affected
operations and investigate audit/journal/event history. Do not update old entries.
Any financial correction needs an approved compensating transaction and a new
reconciliation report. A corrupted projection requires a controlled privileged
maintenance procedure not exposed by this application.
