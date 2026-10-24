# Kafka and event delivery

## Concept in plain English

Kafka stores ordered partition logs. A consumer offset records progress; a database write is a separate durable effect.

## Why backend engineers need it

Publishing reliably requires handling broker outages and duplicate delivery without changing money twice.

## LedgerCore implementation

OutboxPublisher selects due rows FOR UPDATE SKIP LOCKED, waits for acks=all, then marks published. EventConsumer inserts processed_events and event_audit_projection atomically; the listener acknowledges only after return.

## Relevant files and classes

`events/OutboxPublisher.java`, `EventConsumer.java`, `KafkaListenerAdapter.java`, `KafkaConfiguration.java`, `EventInfrastructureIT`.

## Concrete example

A publisher receives an acknowledgment then fails before marking its row. The next attempt publishes the same eventId. The consumer unique key turns the second delivery into a no-op.

## Common mistakes

Marking before send loses events. Committing a dedup marker before its effect loses processing on retry. Kafka keys alone do not establish global or retry ordering.

## Failure scenario

The real broker is paused in the integration test, then unpaused. A one-shot consumer failure rolls back its marker and Kafka redelivers. Poison events are retried indefinitely and require operator intervention.

## Interview question and answer

Why hold an outbox row lock during send? It is simple and bounded, allowing multiple workers with SKIP LOCKED; the cost is a database connection while waiting for the broker.

## Further learning exercise

Inspect event IDs in outbox, processed_events and projection. Design a lease-based publisher and list crash/lease-expiry races before changing code.
