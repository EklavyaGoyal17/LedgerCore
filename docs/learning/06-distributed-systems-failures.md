# Distributed systems failures

## Concept in plain English

Different processes do not share a single atomic commit. A timeout reports missing knowledge, not necessarily a failed operation.

## Why backend engineers need it

Reliable recovery needs durable intent, retry identity and deduplicated effects across each boundary.

## LedgerCore implementation

Financial truth is local to PostgreSQL. EventRecorder stores intent in the outbox. The publisher and consumer recover independently. Faults defines controlled test boundaries only.

## Relevant files and classes

`common/Faults.java`, `events/EventRecorder.java`, `events/OutboxPublisher.java`, `docs/failure-scenarios/recovery.md`.

## Concrete example

The database commits transfer X. The process loses its HTTP response. A retry of key X reads the original result. Kafka can remain unavailable throughout without changing X.

## Common mistakes

Interpreting every timeout as FAILED, attempting rollback after commit, or claiming end-to-end exactly-once delivery hides the uncertainty window.

## Failure scenario

Outbox acknowledgment can disappear after broker persistence. Retrying may duplicate delivery; consumer deduplication prevents a second local projection effect.

## Interview question and answer

Can you make PostgreSQL and Kafka exactly once by setting producer idempotence? No. Producer idempotence does not atomically include the application database mark or a restarted producer identity.

## Further learning exercise

Draw failure windows before/after every durable boundary. Propose a test for full process termination, distinguishing it from the implemented deterministic exception hook.
