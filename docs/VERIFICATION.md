# Executed verification and scope of proof

Fresh verification on **2 November 2026**, Windows / Docker Desktop Linux engine
29.5.3, Temurin **21.0.12.1**, Maven **3.9.16**. The packaged application also runs
Java 21. This is simulated financial infrastructure with no real payment rails.

`mvn -B -ntp clean verify` passed in **7 minutes 42 seconds**:

- **85 tests: 0 failures, 0 errors, 0 skipped**, comprising 23 unit/architecture/
  property/migration-runner cases and 62 real infrastructure integration cases.
- Three jqwik properties executed 1,000 attempts each, with shrinking and recorded
  seeds. Generated database sequences additionally exercise INR and USD.
- JaCoCo instruction coverage: **89.63%**, 4,695 covered / 543 missed instructions.
- SpotBugs Max effort / High threshold: **0 findings**. Spotless, compilation,
  migrations, packaging and coverage reporting passed.
- [Exact suites, cases, seeds and totals](verification/test-results.json).

| Capability | Executed evidence and observed result |
|---|---|
| Financial invariants | FinancialDomainTest / FinancialPropertiesTest: exact precision, supported currencies, balanced journals, legal state transitions; 3,000 jqwik attempts |
| Module boundaries | ArchitectureTest: acyclic modules, domain independence, no financial Redis/Kafka dependency, no exposed JPA entities, unused view-feature restriction |
| Database integrity | FinancialIntegrityIT / LedgerCoreIT: direct SQL corruption, immutable postings, durable replay and matching journal/audit/event evidence guards |
| Atomic transfers | Fault boundaries roll back every technical-failure effect; durable business failures have no journal |
| Concurrent overspending | Ten repeated 1,000 / two 800 races: one success, one rejection, source 200, one successful journal |
| Locks and retries | Opposing transfers plus actual PostgreSQL 55P03 lock timeout and 40P01 deadlock; narrow whole-transaction retry passed |
| Idempotency / reversal | Duplicate/conflicting-payload races, concurrent reversals, response loss and expired-horizon replay preserve one result per key and one completed reversal |
| Overflow / statements | Durable destination-overflow 422; statement ordering and running balances agree |
| Authentication / ownership | Real login, signature/issuer/audience/expiry/subject/current-role/missing-claim and private-resource checks passed |
| Redis interruption | Atomic count/TTL; authentication fails closed and authenticated business requests fail open without compromising financial state |
| Kafka recovery | Retained-storage broker stop/start, acknowledgment loss, consumer rollback/redelivery, durable deduplication |
| Poison events | Bounded retry, durable quarantine, partition progress, failed/successful replay and administrative access checks |
| Actual JVM recovery | ProcessRecoveryIT: separate JVM death before/after commit and fresh-process replay preserve one posting; migration-only process and restricted role passed |
| Existing schema upgrade | LegacyMigrationIT: populated V1 upgrades with preserved history, replay backfill and immutable guards restored |
| Reconciliation | Independent corruption fixtures and generated valid sequences verify projection/business/currency/replay/audit/event checks; no silent repair |
| Packaged Compose | Image build, one-shot bootstrap/migrate and restricted non-root read-only backend startup passed; volumes preserved |
| API acceptance | scripts/demo.py: complete journals, successful/duplicate/insufficient/concurrent movements, balances, statements and CLEAN reconciliation |
| OpenAPI | Structured monetary 201 schemas, representative response/error examples and live export with 16 paths |
| Observability | Connected HTTP -> transfer -> journal and outbox -> Kafka send/receive trace: 11 spans and durable publication/consumption |
| Dashboard / alerts | Live provisioning: 14 panels; seven valid rules; promtool independently verifies backlog and quarantine behavior |
| Dependencies | Native and packaged CycloneDX SBOMs queried against OSV: 150 runtime components, two reviewed unused-feature advisories, zero unreviewed findings |
| Backup safety | Three Python tests passed for binary streams, exclusive backup and safe new-target restore guards |
| Education | All 14 guides, nine ADRs, 60 interview Q&A; referenced files and local documentation links checked |

The [technical requirements map](REQUIREMENTS.md) maps requirements to
implementation and evidence. [Trace evidence](verification/observability.json) and
[resolved dependency scan](verification/dependency-scan.json) retain safe results.

## Performance and logical recovery

All five 60-second k6 modes passed, with five virtual users normally and twenty
for shared-source contention: **16,283 requests, zero HTTP failures**, and CLEAN
reconciliation before/after. The [measured report](verification/sustained-load/README.md)
contains p50/p95/p99, throughput, fixture/hardware details and raw summaries.
Historical two-second smoke outputs remain provenance rather than sustained proof.

The default publisher accumulated a durable backlog during write bursts. The
[delivery observation](verification/event-drain.json) subsequently verified all
**9,047 outbox events published and projected**, with zero pending, unconsumed or
quarantined events. The normal 120/minute rate limit was restored and the backend
recreated healthy. The report explains why zero Kafka lag can coexist with an
unpublished PostgreSQL backlog; no unsupported event-throughput claim is made.

The post-load [logical restore drill](verification/restore-drill.json) restored
**8,995 journals, 17,990 entries and 9,001 transfer/idempotency records** into a
new disposable database. Counts matched the source, with zero unbalanced journals,
orphans, projection mismatches or unfinished requests. Only that disposable target
was removed. The [query plans](architecture/query-plan-evidence.txt) include the
actual statement window on the busiest account's 6,212 entries.

## GitHub delivery

The [GitHub review](operations/github-review.md) records the initial-main CI result
and all ten dependency proposals. The completion workflow additionally builds the
Docker image and requires a Java 21 runtime. The hardened implementation commit
`a657c40aff69d6b2c1ba6de9fd8df7553677fe0f` **passed** its
[push-triggered CI run](https://github.com/EklavyaGoyal17/LedgerCore/actions/runs/41459540117),
including the image build, runtime check, safety tests and OSV scan. The
[machine-readable CI result](verification/ci.json) records that exact SHA.
Subsequent evidence changes trigger verification again; see the
[Actions history](https://github.com/EklavyaGoyal17/LedgerCore/actions/workflows/ci.yml)
for the latest revision. Dependency findings refer to the independently executed
resolved-runtime OSV scan and its documented scope.

Java 24/26 and independent Boot 4/springdoc 3 proposals are deferred to deliberate
migrations. Compatible pins are retained; optional build-tool upgrades are
maintenance rather than missing product phases. No dependency PR was merged
solely because its initial-branch check was green.

## Reproduce

Install JDK 21 and Maven 3.9+, configure `JAVA_HOME`/`PATH`, and start Docker before
native integration tests. Compose defaults to API port 8080 and 10% trace sampling;
`API_PORT` and `TRACE_SAMPLE_PROBABILITY` can override them. The recorded experiment
used port 8088 and full trace sampling. Ordinary operation uses 120 requests/minute;
the isolated benchmark temporarily raises that limit and restores it afterward.

```powershell
java -version  # must report Java 21
mvn -version  # must use the same JDK
mvn -B -ntp clean verify
python scripts/scan-dependencies.py
python scripts/test_database_tools.py
docker compose up --build -d --wait --wait-timeout 300
$env:BASE_URL = 'http://localhost:8080' # match the selected API_PORT
python scripts/demo.py
python scripts/check-observability.py
python scripts/restore-drill.py
```

Docker builds its own Java toolchain. The [OpenAPI export](api/openapi.json) records
the verification server URL; `/v3/api-docs` generates the contract. Testcontainers uses
isolated databases rather than Compose data. Restore comparisons require writers
paused.

## Limits and interpretation

ProcessRecoveryIT kills actual application JVMs while PostgreSQL remains running.
Kafka stop/start preserves broker storage. Neither establishes recovery after host
power loss, database destruction or multi-node failover. Defined fault hooks and
generated seeds provide regression evidence, not exhaustive state-space proof.

Runtime cannot perform DDL, truncate or mutate immutable financial history; the
host administrator still controls Docker and bootstrap credentials. Logical backup
and restore do not implement physical/PITR recovery. Shared deployment needs TLS,
managed secrets, protected monitoring ingress, encrypted off-host retention and
independent review.

Outbox delivery is at least once with durable idempotent consumer effects. Bounded
publisher waits hold a database connection; global event ordering is not promised.
Poison events are quarantined after bounded retries. Consumer processing failures
and successful operator replays retain audit evidence; infrastructure failures
before/at replay commit may return a technical error without a separate failed
replay-attempt row.

Full-history reconciliation and statement windows suit educational datasets.
Bounded scans/checkpoints and archival preserving permanent financial uniqueness
are future scaling work. JWT uses configurable HS256 with 15-minute access tokens;
MFA, refresh tokens and asymmetric rotation are deployment extensions. No FX,
external rails, deposit reversal or chargeback flow is provided.

Two dated Spring MVC advisory exceptions concern unused fragment/XSLT view
features and are backed by architecture restrictions. Passing OSV under this policy
means no unreviewed findings, not advisory-free or certified banking software. See
[ADR 009](adr/009-dependency-security.md).
