# Distributed failures and recovery boundaries

## Concept and purpose

Processes fail independently. A durable local commit does not atomically include
an HTTP response, a Kafka acknowledgment, or a consumer's offset commit. Reliable
recovery starts by identifying which fact survives each boundary and which action
can safely be repeated.

## Durable facts in LedgerCore

The PostgreSQL transaction records financial truth plus publication intent.
`EventRecorder` inserts the audit and outbox envelope with the financial outcome.
`OutboxPublisher` later sends the retained event ID to Kafka. `EventConsumer`
commits a unique processed marker and audit projection together. A client,
publisher, and consumer therefore each have a different retry identity.

| Failure boundary | Durable fact | Recovery |
|---|---|---|
| Before financial commit | no visible financial effect from attempt | repeat original request key |
| After commit, before response | complete outcome and replay result | replay key; never infer failure from timeout |
| Broker receives event, publisher loses ack/mark | transfer already committed; event may be delivered | publish same event ID again |
| Consumer fails before DB commit | neither marker nor projection survives | redeliver and process |
| Consumer commits, offset acknowledgment is lost | marker and projection survive | duplicate marker makes delivery a no-op |
| Poison record exhausts bounded retries | quarantine stores raw record and coordinates | repair cause, perform audited operator replay |

Kafka outages cannot roll back an already committed transfer. A pending outbox is
a delayed downstream effect, not a failed payment. Likewise, a successful outbox
publication does not by itself prove that the consumer projection is complete;
inspect unresolved quarantine and consumer state separately.

## Three strengths of failure experiment

1. `Faults`/`TestFaults` inject deterministic exceptions before transaction,
   after validation, during journal insertion, after outbox, before/after commit,
   around publication, and during consumer handling. Production `Faults` is a
   no-op, with no public fault-injection endpoint or header.
2. `ProcessRecoveryIT` starts actual child JVMs, halts at defined financial
   boundaries, and launches fresh processes to replay the original request.
   Inspect its retained balances, journal count, event count, and replay equality.
   PostgreSQL stays running with the same stored data while the application JVMs
   terminate and restart; this test does not restart the database container.
3. `EventInfrastructureIT` uses real Kafka/Redis containers for acknowledgment
   loss, outages, broker restart, duplicates, poison records, and replay behavior.
   These are stronger integration observations than a mocked Kafka exception.

None of those is a physical power-loss, multi-node failover, or storage-corruption
certification. The distinction describes the actual experiment boundary rather
than reducing the value of the implemented tests.

## Backup is a recoverability experiment

`scripts/backup.py` creates a PostgreSQL custom-format dump plus a SHA-256 manifest
without overwriting an existing file. `restore.py` restores only into a new,
explicitly named `ledgercore_restore_*` database. `restore-drill.py` compares
source/restored counts and accounting invariants, then removes only the disposable
UUID-named database created by that drill. A dump command returning zero proves
less than a restore followed by invariant checks.

Run a drill with financial writers paused when requiring exact source-versus-copy
counts. A consistent dump can legitimately represent an earlier point than a
later live snapshot if writing continues. Do not respond by weakening the check
or deleting live volumes. This tooling does not provide offsite retention,
encryption, continuous WAL archiving, or tested recovery-time objectives.

## Questions, mistakes, and exercise

- **Does producer idempotence create an atomic PostgreSQL/Kafka transaction?** No.
  It cannot join the local outbox mark to broker persistence across restarts.
- **Can you roll back an HTTP timeout?** Not if its database commit already
  succeeded; query/replay first, then use a compensating operation if appropriate.
- **Why record failed manual replay separately?** Its consumer transaction must
  roll back, while evidence of the operator's failed attempt must remain durable.
- **What does a clean reconciliation establish?** The inspected snapshot agrees
  with its checks; it does not prove every external event was consumed or every
  future infrastructure failure is covered.

Common mistakes are treating missing acknowledgments as failed money movement,
claiming end-to-end exactly once, replacing persistent infrastructure in a
"restart" test, and deleting queues to eliminate alarming backlog metrics.

Run the selected `ProcessRecoveryIT` and `EventInfrastructureIT` suites. Draw a
vertical line at each durable commit and place a process crash immediately on
either side. For each case name the surviving key/event/marker and the next safe
operation. Then run `python scripts/restore-drill.py` against your local simulation
with writers paused and retain its actual output as evidence.
