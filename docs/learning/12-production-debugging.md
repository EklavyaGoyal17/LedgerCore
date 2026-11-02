# Production debugging with durable evidence

## Concept and purpose

Start with a precise symptom and timeline. Preserve evidence before modifying
behavior. A missing response, delayed publication, quarantined record, and real
reconciliation mismatch require different safe actions.

## Start with one transfer

Retain principal, operation, original key, correlation ID, and any known transfer
ID. Replaying the original request safely establishes its durable outcome. If it
completed, do not issue a new-key payment to make sure. Inspect journal entries,
projections, audit, and outbox through their linked IDs. A FAILED outcome retains
its original code even if funds changed later.

| Symptom | Inspect | Safe next action |
|---|---|---|
| Unknown response | durable key/result and status | replay same key |
| Slow transfers | Hikari, blockers, transaction age | identify cause before increasing pool |
| Pending outbox | due time, attempts, broker | restore publication; retain evidence |
| Poison consumer record | quarantine, coordinates, replay audit | repair cause and audited admin replay |
| Discrepancy | report/category/affected IDs | contain, investigate; no silent repair |
| Recovery concern | backup manifest and drill | restore into new database and verify |

## Read-only database investigation

Use a privileged diagnostic connection in the local simulation; runtime
credentials intentionally have fewer permissions.

```sql
SELECT pid,state,wait_event_type,wait_event,
       clock_timestamp()-xact_start AS transaction_age,
       pg_blocking_pids(pid) AS blockers
FROM pg_stat_activity
WHERE datname=current_database() AND pid<>pg_backend_pid()
ORDER BY xact_start NULLS LAST;

SELECT aggregate_id,event_type,attempts,next_attempt_at
FROM outbox_events WHERE published_at IS NULL
ORDER BY next_attempt_at,created_at LIMIT 25;
```

An abandoned psql transaction can be the blocker. A row-lock timeout differs
from a pool acquisition timeout. Kafka waiting can consume publisher connections
and worsen pool pressure. Compare these with Redis timeout, CPU/GC, query plans,
and request durations. Do not export tokens or sensitive SQL unnecessarily.

## Recovery has an audit trail

The administrator quarantine API accepts a replay reason and optional corrected
envelope. Original payload/coordinates remain immutable. A failed replay rolls
back consumer effects, then retains its failed operator attempt separately.
Successful projection, resolution, and replay audit commit together. Replaying
an event does not post financial money again.

`ReconciliationService` records `PROJECTION_MISMATCH`, posting/reversal mismatch,
replay mismatch, and missing expected audit/event categories. Test-only corrupted
fixtures use ephemeral owner privileges. No public API disables triggers or
repairs journals. A financial correction uses compensation; projection repair
requires a reviewed maintenance procedure beyond the ordinary API.

## Questions, mistakes, and exercise

- **Why not restart everything first?** You may lose timing evidence while the
  durable problem remains.
- **Why not delete queues/backlogs?** Pending intent and retained financial
  evidence would be lost; the application deliberately protects these rows.
- **Why restore after backing up?** File existence/checksum alone does not prove
  recovery with valid accounting relationships.
- **Why cannot runtime disable its protection?** Separate ownership limits the
  consequences of API or credential compromise.

Run one broker-outage or lock-contention experiment. Write an incident report
with symptom, timeline, guarantee affected, evidence, mitigation, observed
recovery, and regression test. Include safe IDs and actual artifacts, omit
passwords/tokens, and label hypotheses separately from observations.
