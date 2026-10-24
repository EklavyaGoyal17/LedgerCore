# ADR 006: Publish through a transactional outbox

Status: accepted.

## Context

Database commit and Kafka acknowledgment cannot be one ordinary local transaction.

## Decision

Insert outbox with financial writes, publish with acks=all, mark only after acknowledgment, and deduplicate consumers durably.

## Alternatives

Publishing before commit can produce events for rolled-back money. Publishing only after commit without an outbox can lose events. XA would add substantial operational coupling.

## Consequences and verification

Delivery is at least once. A lost acknowledgment creates duplicates. Workers use SKIP LOCKED; aggregate keys do not guarantee retry order. Failed poison events block their partition until handled.

Read the corresponding implementation and tests before changing this decision. `docs/VERIFICATION.md` records actual execution evidence.
