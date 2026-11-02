# Observability that explains financial failures

## Concept and purpose

Metrics summarize populations, logs describe events, and traces connect timed
operations. Durable financial records prove what committed. Process telemetry
cannot replace accounting totals because a crash can occur after commit but
before a counter increment or log export.

## Follow the implemented signals

`observability/RequestContextFilter` validates bounded safe correlation IDs,
generates a request ID, returns the correlation header, and records method,
status, and elapsed time. Its finally block removes MDC fields so reused request
threads cannot inherit another user's context. Structured JSON excludes raw
credentials/payloads; safe IDs connect diagnostics to durable audit/outbox rows.

`TransferEngine` creates a transfer observation and duration timer. Transaction
synchronization increments committed outcome counters after commit. Replaying a
key does not register another financial commit. Retries have their own counter.

| Signal | Interpretation | Check alongside it |
|---|---|---|
| HTTP latency/errors | client-visible service behavior | route/status, pool, Redis, locks |
| committed outcomes/categories | observed financial commits | durable totals after process loss |
| Hikari active/pending | connection demand | blockers and outbox network waits |
| pending outbox/failures | publication delay | broker and due-retry timestamps |
| unresolved quarantine | failed consumer work remains | replay audit and consumer lag |
| reconciliation discrepancies | inspected snapshot disagrees | report categories/affected IDs |

Grafana panels are provisioned under `infrastructure/grafana`; Prometheus scrapes
Actuator. Prometheus normalizes metric names and adds counter/timer suffixes;
inspect actual `/actuator/prometheus` output before writing queries. Never label
metrics with account/transfer/event UUIDs or emails: their unbounded cardinality
makes monitoring expensive and hard to aggregate.

## Asynchronous trace context

`EventRecorder` retains W3C traceparent in the outbox. `OutboxPublisher` extracts
it when starting a publication span and uses Kafka template instrumentation.
Listener observations connect handling where supported; Tempo receives OTLP
traces in the local stack. Sampling can omit a successful request. A collector
outage must not change financial results.

An event retry may occur much later than its HTTP request. That trace describes
causal work, not a transaction holding every system open. Inspect configuration
and captured spans before claiming one span per raw JDBC statement: framework
instrumentation and explicit observations do not prove that granularity.

## Diagnostic example

Stop local Kafka and create a fresh simulated transfer. Inspect its committed
outcome and pending outbox. Financial success can coexist with rising backlog
and publication failures. Restart Kafka retaining its volume and observe due
retries. Check quarantine separately: a drained outbox can coexist with a poison
record awaiting repair. Reconciliation independently inspects financial state.

## Questions, mistakes, and exercise

- **Why count after commit?** A deferred invariant can reject an apparently
  successful attempt at commit; earlier counting would report a false success.
- **Can after-commit counters undercount?** Yes, a crash can precede their emission.
- **Why clean MDC in finally?** Success, rejection, and exception paths all reuse
  servlet threads.
- **Why two requests for one transfer?** One can be an idempotent replay; request
  throughput and new financial postings are distinct quantities.

During an isolated local experiment, enable full sampling, export a real trace,
and annotate observed spans and missing visibility. Produce a timeline with one
correlation ID, transfer ID, backlog, and pool pressure. Separate observations
from hypotheses; do not invent spans, counts, or financial totals from telemetry.
