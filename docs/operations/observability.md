# Observability verification and incident signals

LedgerCore exports application and Kafka-client metrics through Micrometer to `/actuator/prometheus`, scrapes them every five seconds with Prometheus, provisions a Grafana dashboard, and sends sampled OTLP traces to Tempo. Compose binds the API, Prometheus and Grafana to loopback. Tempo is accessed through the provisioned Grafana datasource rather than exposing another host port. These services are local diagnostic infrastructure, not a highly available monitoring deployment.

## Dashboard semantics

Open Grafana's provisioned **LedgerCore simulation — financial operations** dashboard (`uid=ledgercore`). It includes:

- HTTP p95/p99 latency from histogram buckets, excluding actuator traffic, and financial command p95/p99 latency from `ledgercore_transfer_duration_seconds_bucket`. The financial command timer includes deposits, transfers, reversals, retries and response replays. Five-minute histogram estimates require traffic; absent/NaN quantiles are not zero-latency measurements.
- API throughput, HTTP 5xx ratio, committed financial outcomes by safe category, and committed business rejection ratio. Insufficient funds is a valid business rejection and must not be confused with an internal server error. Outcome counters increment only after database commit; idempotent response replays do not create another committed outcome.
- Durable pending outbox rows, unconfirmed publication rate, and unresolved consumer quarantine. These measure different boundaries: a broker outage can leave valid transfers committed with unpublished outbox events; a poison record can be successfully published and then quarantined without appearing in the pending outbox.
- Kafka consumer maximum fetch lag, database pool utilization and pending connection callers, reconciliation discrepancy observations, and target scrape health. Kafka lag becomes available after consumer partition assignment. A missing lag series means unavailable telemetry, never an inferred zero. Successfully quarantined records no longer contribute to broker lag.

The reconciliation counter is cumulative discrepancy observations across runs, not an unresolved-incident gauge. A historical positive value must be interpreted alongside persisted run reports. Database pool waits identify connection contention; inspect PostgreSQL row locks and transaction duration before increasing pool size.

## Alerts

`infrastructure/prometheus/rules/ledgercore.yml` defines backend unavailability, sustained unpublished outbox backlog, unresolved quarantine, sustained HTTP server errors with a minimum traffic floor, sustained database pool waiters, newly observed reconciliation discrepancies, and sustained Kafka consumer lag. Thresholds are explicit local demonstration defaults and need workload-specific tuning. The Prometheus rules page shows pending/firing alerts. No Alertmanager receiver or external notification delivery is configured.

Outbox backlog and quarantine deliberately use separate alerts. Clearing one does not clear the other. The synthetic `alerts-test.yml` exercises both cases: pending publication with no quarantine, and resolved publication with an unresolved consumer effect. It uses generated time series and does not corrupt the live database or inject poison events into the running broker.

Validate configuration and rule syntax after changes:

```powershell
docker compose exec -T prometheus promtool check config /etc/prometheus/prometheus.yml
docker compose exec -T prometheus promtool check rules /etc/prometheus/rules/ledgercore.yml
```

Compose mounts the rules directory separately. Recreate Prometheus after adding/changing mounts or restart it after changing its startup-loaded configuration. Grafana periodically reloads its provisioned dashboard file.

## Repeatable end-to-end check

Run against the local Compose deployment:

```powershell
python scripts/check-observability.py
```

The script reads `.env` followed by environment overrides. Supported connection overrides include `BASE_URL`, `PROMETHEUS_URL`, `GRAFANA_URL`, `API_PORT`, `PROMETHEUS_PORT`, `GRAFANA_PORT`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `GRAFANA_USER` and `GRAFANA_PASSWORD`. It uses the administrator and Grafana credentials in memory and does not print tokens, passwords, HTTP headers or API response bodies on failure.

The check:

1. Runs Prometheus alert behavior tests through `promtool` inside the existing container.
2. Confirms Grafana dashboard/datasource provisioning, a healthy Prometheus target and loaded alert rules.
3. Creates two simulation accounts, funds one with 25.00 INR, and transfers 1.23 INR. These are retained simulation records; repeated checks create new accounts and keys.
4. Verifies balanced journal entries through the API and publication plus consumer projection through PostgreSQL evidence.
5. Supplies a sampled W3C `traceparent` for that one transfer, fetches the actual trace from Tempo through Grafana, and checks the span ancestry: HTTP → financial command → journal; financial command → outbox publisher → Kafka producer → Kafka consumer. This tests retained outbox trace context and propagation across the broker, rather than merely observing unrelated traces in Tempo.
6. Confirms actual metric series exist for HTTP/financial histograms, committed outcomes, pending outbox, quarantine, database pool, and Kafka consumer lag. Missing expected telemetry fails the check.

The sampled incoming context allows deterministic verification without requiring every local request to be sampled. Trace export and scraping are asynchronous, so the script polls bounded readiness conditions. The output contains simulation IDs, trace ID, span names, loaded alert names, and series counts. The default evidence file is `.tools/observability-check.json`; use `--output PATH` to select another location. Open the trace ID in Grafana Explore using the Tempo datasource.

These checks prove the configured local diagnostic path works for a real transfer. They do not establish a latency SLO, sustained throughput, production trace retention, alert delivery, or monitoring availability during a complete host outage. Trace sampling can omit other requests; retained Tempo data is local and configured for 24 hours.

## Investigation order

When HTTP latency rises, compare histogram tails with pool waiters, database locks and query plans, then inspect the transfer trace and its correlation ID. A Kafka outage should be visible as outbox backlog/publication failures while the financial transaction trace still commits. A successful broker delivery followed by failed projection should appear in quarantine; follow [the event operations runbook](events.md). For a reconciliation alert, preserve the report and affected references and investigate authoritative journals before proposing any correction.
