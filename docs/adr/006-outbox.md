# ADR 006: Publish through a transactional outbox

Status: accepted.

## Context

Database commit and Kafka acknowledgment cannot be one ordinary local transaction.

## Decision

Insert outbox with financial writes, publish with acks=all, mark only after acknowledgment, and deduplicate consumers durably.

## Alternatives

Publishing before commit can produce events for rolled-back money. Publishing only after commit without an outbox can lose events. XA would add substantial operational coupling.

## Consequences and verification

Delivery is at least once. A lost acknowledgment creates duplicates. Workers use SKIP LOCKED; aggregate keys do not guarantee retry order. A failed scheduled publication ends that batch, so a broker outage costs at most one bounded send attempt per poll. Unconfirmed events remain pending with capped backoff.

Consumers retry a failed record a bounded number of times and then commit its original payload and Kafka coordinates to PostgreSQL quarantine before allowing offset progress. If quarantine fails, the recoverer throws and delivery remains eligible for retry. A unique topic/partition/offset key tolerates a crash between quarantine commit and offset commit. This avoids a permanent poison record blocking all later partition traffic while preserving durable recovery evidence. Administrator replay goes through the same idempotent consumer; successful processing, resolution, and replay audit commit together. Failed attempts are retained separately after rollback. Original quarantine payloads and replay audit are immutable. See [the event operations runbook](../operations/events.md).

`EventInfrastructureIT` stops and starts the same Kafka container with a stable published host port, tests duplicate delivery and consumer rollback, and sends a poison record followed by a valid record on the same partition. It exercises both original failed replay and corrected audited replay. The local broker has a single replica; this test establishes recovery from broker process/container interruption with retained storage, not cluster failover or protection against destroyed storage.

Read the corresponding implementation and tests before changing this decision. `docs/VERIFICATION.md` records actual execution evidence.
