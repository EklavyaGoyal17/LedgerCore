# Feature and verification checklist

Executed locally on **24 October 2026**, Windows 11 / Docker Desktop Linux engine,
using Temurin **21.0.12.1**, Maven **3.9.16** and real Testcontainers infrastructure.
The packaged Docker application also reports Java 21.0.12.1.

`mvn -q spotless:apply clean verify` completed with exit code **0**:

- **44 tests, 0 failures, 0 errors, 0 skipped**.
- 12 domain/unit cases, 30 PostgreSQL/API integration cases, 2 Kafka/Redis
  infrastructure scenarios (each scenario asserts multiple failure/recovery states).
- JaCoCo instruction coverage: **88.40%** (3,560 covered; 467 missed).
- Formatting, compilation, unit/integration execution, coverage report and jar packaging passed.
- [Machine-readable case list and totals](verification/test-results.json).

| Capability | Implemented evidence | Observed result |
|---|---|---|
| Java 21 application | pom.xml, Dockerfile | native clean verify and image build passed |
| Exact money, journal validation, state transitions | FinancialDomainTest | all 12 cases passed, including 1,000 generated journals |
| Flyway and database protections | V1 migration, LedgerCoreIT | migrations executed; mutation/append, unbalanced commit and projection corruption guards verified |
| Atomic transfers and durable failures | TransferEngine, Idempotency | rollback hooks at five precommit boundaries and durable 422 replay passed |
| Concurrent overspending | repeatedConcurrentWithdrawalsCannotOverspend | 10 repeated races: one 800 succeeds from 1,000, one fails; source 200 and one successful journal |
| Opposite-direction transfers | ordered customer row locks | 10 bidirectional races passed without deadlock |
| Concurrent duplicate requests | unique reservation plus eight-caller race | same transfer ID, one successful posting; changed payload rejected |
| Lost response and retention | AFTER_COMMIT and expired-horizon tests | committed result replayed without duplicate posting; old keys remain reserved |
| Reversals | new compensating journals | funds, single-reversal and replay cases passed |
| Security | BCrypt, real JWT, ownership and roles | signature, expiry, issuer, audience, subject, disabled identity, forged role and private resource checks passed |
| Redis | real script and pause/unpause | atomic count/TTL and fail-open business / fail-closed authentication policies passed |
| Kafka and outbox | real broker pause, acknowledgment-loss hook, real listener | publication recovered, consumer rollback/redelivery passed, duplicate effect rejected |
| Reconciliation | repeatable snapshot and controlled corruptions | clean normal flow; projection mismatch and wrong transfer amount independently detected; no silent repair |
| Generated transaction sequence | 100 seeded money operations | nonnegative projections, conserved customer liabilities and clean reconciliation |
| OpenAPI | generated contract test plus live export | 12 paths; HTTP 201 transfer response references the structured Transfer schema |
| Full Compose | PostgreSQL, Redis, Kafka, backend, Prometheus, Grafana, Tempo | docker compose up with build/wait exited 0; all services running |
| API acceptance demo | scripts/demo.py against packaged application | successful, duplicate, insufficient, concurrent, statement and reconciliation assertions passed |
| Metrics, dashboards and traces | live Actuator / Prometheus / Grafana / Tempo | readiness UP, target up, Grafana database ok, 20 traces found at inspection; committed/outbox/pool/consumer-lag metrics present |
| Event pipeline after demo | SQL inspection | 9 outbox events, 9 projected events, 0 pending at inspection |
| Query plans | EXPLAIN ANALYZE BUFFERS after demo/load smoke | captured actual plans in [query-plan-evidence.txt](architecture/query-plan-evidence.txt); tiny dataset, not throughput proof |
| k6 workloads | five modes, one VU, two seconds each | all checks and thresholds passed; post-load reconciliation CLEAN; [raw outputs](verification/k6-smoke) |
| CI | SHA-pinned GitHub Actions | configured; **not executed on GitHub** |
| Dependencies | pinned BOM/images/plugins and Dependabot | updates configured; vulnerability scanning **not executed** |
| Education | 14 guides, 8 ADRs, 44 interview answers | written against implemented files and behavior |

## Reproduce in this workspace

Port 8080 belongs to another local project. The ignored `.env` uses API_PORT=8088
and TRACE_SAMPLE_PROBABILITY=1 for verification. Compose otherwise defaults to 8080
and 10% trace sampling. Existing unrelated services were left running.

```powershell
$env:JAVA_HOME = (Get-ChildItem .tools\jdk21 -Directory | Select-Object -First 1).FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn clean verify
docker compose up --build -d --wait --wait-timeout 300
$env:BASE_URL = 'http://localhost:8088'
python scripts/demo.py
```

The workspace-local JDK download is ignored and not part of the repository. A fresh
clone needs JDK 21/Maven only for native tests; Docker builds its own Java toolchain.
The live OpenAPI export is [api/openapi.json](api/openapi.json); its localhost server
URL reflects this verification environment. The live endpoint regenerates it.

## Scope of proof and operational limits

Tests use real PostgreSQL, Kafka and Redis, without H2. Fault hooks model defined
process-failure boundaries using deterministic exceptions. Pause/unpause exercises
infrastructure unavailability while preserving container identity and storage;
it does not prove host power-loss durability, multi-region failover or broker quorum
loss. Generated sequences use fixed seeds, not an exhaustive state-space proof.
The short k6 runs are script/API smoke tests, not sustained-load benchmarks; no
production capacity or latency target is claimed.

- Local deployment has one database and broker, plaintext internal connections and
  loopback host ports. It is explicitly simulated infrastructure.
- Flyway/runtime share a schema owner locally. Runtime least privilege, separate
  migration credentials, backup/restore and failover procedures remain deployment work.
- JWT uses an environment-provided HS256 secret; MFA, refresh and key rotation are absent.
- Publisher holds one connection while waiting for acknowledgment. Poison events
  retry indefinitely and require an operator procedure. Retry ordering is not guaranteed.
- Full-history reconciliation and statement windows target educational datasets.
  Bounded scans, archival and retention tooling need separate implementation.
- Idempotency keys remain reserved indefinitely. No unsafe TTL deletion is scheduled.
- Kafka/Redis tests include interruption and redelivery, but not a whole application
  OS-process kill or full multi-broker infrastructure replacement.
- There are no external money rails, FX, deposit reversal or chargeback flows.

These results describe local verification before the initial repository publication.
GitHub Actions results must be checked separately after pushing; local verification
does not establish a remote CI result. No external-host deployment was performed.
