# Kafka and event delivery

## Concept in plain English

Kafka stores ordered partition logs. A consumer offset records progress; a database write is a separate durable effect.

## Why backend engineers need it

Publishing reliably requires handling broker outages and duplicate delivery without changing money twice.

## LedgerCore implementation

OutboxPublisher selects due rows FOR UPDATE SKIP LOCKED, waits for acks=all, then marks published. Each send wait is bounded and a failed scheduled send ends its batch. EventConsumer inserts processed_events and event_audit_projection atomically; the listener acknowledges only after return. Duplicate IDs with conflicting event type or aggregate are rejected rather than silently accepted.

## Relevant files and classes

`events/OutboxPublisher.java`, `EventConsumer.java`, `KafkaListenerAdapter.java`, `KafkaConfiguration.java`, `EventOperations.java`, `EventOperationsController.java`, `EventInfrastructureIT`.

## Concrete example

A publisher receives an acknowledgment then fails before marking its row. The next attempt publishes the same eventId. The consumer unique key turns the second delivery into a no-op.

## Common mistakes

Marking before send loses events. Committing a dedup marker before its effect loses processing on retry. Kafka keys alone do not establish global or retry ordering.

## Failure scenario

The integration test stops and starts the same real broker container, preserving its stored logs. It explicitly binds a selected free host port before startup because Docker can reassign dynamic port bindings on restart; a changed advertised listener would test a permanent configuration error instead of a recoverable outage. A one-shot consumer failure rolls back its marker and Kafka redelivers.

A poison event gets bounded retries followed by durable PostgreSQL quarantine. Only successful quarantine allows later records on that partition to proceed. If the database cannot store recovery evidence, the recoverer throws and the Kafka record stays eligible for retry. An administrator investigates and replays through the same consumer, optionally providing a corrected envelope and a reason. Original evidence and both failed and successful replay attempts are retained. See [event operations](../operations/events.md).

## Interview question and answer

Why hold an outbox row lock during send? It is simple and bounded, allowing multiple workers with SKIP LOCKED; the cost is a database connection while waiting for the broker.

## Further learning exercise

Inspect event IDs in outbox, processed_events and projection. Design a lease-based publisher and list crash/lease-expiry races before changing code.

Trace a poison event through `failed_kafka_events` and `event_replay_attempts`. Explain why recording a failed replay in the rolled-back consumer transaction would lose the failure audit, and why LedgerCore writes that failed attempt only after rollback completes.
