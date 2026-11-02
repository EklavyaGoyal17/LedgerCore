# Event delivery and recovery

LedgerCore uses **at-least-once delivery**. The ledger transaction writes its audit record and outbox event in PostgreSQL. Kafka publication happens afterward. `OutboxPublisher` marks the event published only after Kafka acknowledges the send. Kafka unavailability therefore delays the audit projection without preventing financial posting.

Publication has a configurable bounded wait (`ledgercore.events.publish-wait-seconds`, default 12), bounded batch size (`batch-size`, default 25), and exponential retry delay capped at 256 seconds. The producer's `max.block.ms` separately bounds time waiting for metadata/buffer availability; it can add to the future wait. A transaction holds one outbox row lock during publication, and multiple publishers use `FOR UPDATE SKIP LOCKED`. Financial requests never wait on those outbox row locks. Unconfirmed events remain pending indefinitely; no attempt limit discards them. Monitor `ledgercore_outbox_pending` and `ledgercore_outbox_publication_failures_total` and investigate a growing backlog. This simple design trades a database connection and row lock during the bounded network wait for explicit ownership without a lease protocol.

An acknowledgment may arrive after a publisher timeout, or the process may terminate after Kafka acceptance but before `published_at` commits. Both cause duplicate delivery. Kafka producer idempotence reduces duplicates from producer retries but does not eliminate this database/Kafka commit gap. `EventConsumer` inserts the event ID into `processed_events` and updates `event_audit_projection` in one PostgreSQL transaction. On redelivery the unique event ID makes the projection operation a no-op. A consumer failure rolls back both writes. These events do not initiate financial posting. No exactly-once cross-system guarantee is claimed. The projection does not depend on event order; concurrent publishers can reorder events, including events for the same aggregate.

## Poison events

The Kafka listener retries a failure three times by default, separated by one second (`consumer-retries` and `consumer-retry-ms`). After that, `EventOperations.quarantine` commits the original payload, topic, partition, offset, and exception category into `failed_kafka_events`. Only a successful recovery return lets the listener advance. If quarantine storage fails, the recoverer throws and Kafka delivery remains eligible for retry. A unique topic/partition/offset constraint tolerates a crash after quarantine commit and before Kafka offset commit. This prevents a permanently invalid record from blocking all later events on that partition without silently losing the original record.

`ledgercore_events_quarantined` reports unresolved records. It must be monitored: quarantining restores partition progress but does not mean the failed event was successfully projected. Database errors or bugs can also enter quarantine after bounded retries. Exception messages and payloads are not logged by this recovery handler. The restricted recovery API returns original payloads for diagnosis; do not put secrets or customer personal data into event envelopes.

## Operator replay

Use an administrator bearer token. The API and application service both enforce the administrator role.

1. `GET /api/v1/admin/events/quarantine?limit=25&offset=0` lists unresolved records. Limits must be 1–100. Offset pagination is appropriate for this small operational queue; use the record ID when investigating one item.
2. `GET /api/v1/admin/events/quarantine/{id}` returns original evidence, including resolved records.
3. Investigate the root cause. Repair application/database infrastructure first. Then `POST /api/v1/admin/events/quarantine/{id}/replays` with `{"reason":"Database permission repaired"}` replays the original payload through the same idempotent consumer.
4. For a malformed envelope, supply a corrected `payload` string (maximum 65,536 characters) and a meaningful `reason` (maximum 500 characters). The original quarantine payload remains immutable. The consumer validates the envelope before writing its projection. A corrected payload is an administrator assertion, so review the original producer and use its original event ID whenever one exists.

Successful replay, deduplication/projection changes, resolution timestamp, and `event_replay_attempts` success evidence commit together. A failed replay rolls back the consumer and records a separate immutable failed attempt; the endpoint returns 422 and leaves quarantine unresolved. Resubmitting a resolved record returns the resolved state without repeating its effects or recording another success. Row locking serializes competing replays. Neither automatic recovery nor manual replay writes journals, balances, or transfers.

Quarantine evidence and replay history cannot be rewritten/deleted through ordinary database writes. Successful and failed attempts record the administrator ID, reason, attempted payload, time, and outcome. Retain these tables and `processed_events` with the financial evidence; deleting deduplication markers while Kafka can replay older messages weakens duplicate protection. Operational backups must include the outbox, processed events, quarantine, replay audit, and Kafka storage.

## Verified failure experiment

`EventInfrastructureIT` uses real PostgreSQL, Kafka, and Redis Testcontainers. Its Kafka outage test stops and starts **the same Kafka container**, preserving its log directory and broker identity. A transfer commits while the broker is stopped, an unconfirmed publication remains pending, and publication catches up after restart. Test-only fault hooks exercise acceptance before the publisher records acknowledgment and a consumer failure after its deduplication insert. The assertions verify one projection per outbox event, unchanged financial totals, and no duplicate financial transfers.

The poison-event test sends malformed and valid events to the same Kafka partition. The malformed event enters durable quarantine after bounded retries and the following valid event is consumed. Tests also exercise duplicate quarantine calls, failed replay auditing, consumer rollback during replay, successful corrected replay, retry after resolution, service-level authorization, pagination validation, and database rejection of evidence mutation.

Run this suite with Java 21 and Docker available:

```powershell
mvn test-compile failsafe:integration-test failsafe:verify '-Dit.test=EventInfrastructureIT'
```

The default test profile disables listener auto-start; the test starts listeners deliberately. Fault hooks used by these experiments are configured only in test source. A stopped/restarted local single-node broker validates the recovery mechanism; it does not establish multi-broker replication, disaster recovery, or zero data loss under storage loss. Production Kafka must use an appropriate replicated topic and broker policy; the local development topic deliberately uses one replica.
