# Observability

## Concept in plain English

Metrics summarize populations, logs describe events and traces link timed operations across boundaries.

## Why backend engineers need it

A successful accounting design still needs evidence to diagnose slow or failed requests.

## LedgerCore implementation

RequestContextFilter adds sanitized correlation/request IDs and durations; Boot emits JSON. TransferEngine creates an observation. HTTP/Kafka instrumentation exports traces and outbox stores trace context. Prometheus scrapes Actuator; Grafana includes pool/backlog/outcomes.

## Relevant files and classes

`observability/RequestContextFilter.java`, `events/EventRecorder.java`, `infrastructure/grafana`, `application.yml`.

## Concrete example

A broker outage leaves financial commits healthy while outbox backlog and publication failures increase. The pool panel can reveal publisher connection occupancy affecting HTTP latency.

## Common mistakes

Labeling metrics by transfer/account UUID creates unbounded cardinality. Counting a precommit attempt as a committed success gives misleading outcomes. Logging raw payloads can leak credentials.

## Failure scenario

A missing tracing backend must not change a committed financial result. Sampling means some requests have logs without retained traces. Pending-outbox gauge queries the database and should be watched at larger scale.

## Interview question and answer

Can metrics prove exact financial totals? No. Process counters can miss crashes; authoritative totals and reconciliation come from durable database records.

## Further learning exercise

Stop Kafka and correlate backlog, failures and pool pressure. Export a trace from HTTP through the transfer observation and document which spans are automatically instrumented.
