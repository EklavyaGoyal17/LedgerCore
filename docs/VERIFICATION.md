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

The [master-prompt checklist](MASTER_PROMPT_CHECKLIST.md) maps requirements to
implementation and evidence. [Trace evidence](verification/observability.json) and
[resolved dependency scan](verification/dependency-scan.json) retain safe results.

## Performance and logical recovery

The five-mode sustained experiment and post-load logical restore drill are being
recorded separately from the completed clean build. Their results will be added
after all modes, reconciliation and restore verification finish. Historical
two-second smoke outputs are retained for provenance, not sustained-load proof.

## GitHub delivery

The [GitHub review](operations/github-review.md) records the initial-main CI result
and all ten dependency proposals. The completion workflow additionally builds the
Docker image and requires a Java 21 runtime. Check the [Actions history](https://github.com/EklavyaGoyal17/LedgerCore/actions/workflows/ci.yml)
for the exact delivered SHA; the initial commit's success does not validate these
changes. Private Dependabot-alert inspection returned 403 with the available
credential; no private-alert state is claimed. The resolved-runtime OSV scan was
executed independently.

Java 24/26 and independent Boot 4/springdoc 3 proposals are deferred to deliberate
migrations. Compatible pins are retained; optional build-tool upgrades are
maintenance rather than missing product phases. No dependency PR was merged
solely because its initial-branch check was green.

## Reproduce

Another local project uses port 8080. The ignored `.env` uses API_PORT=8088 and
TRACE_SAMPLE_PROBABILITY=1; Compose defaults are 8080 and 10% sampling. Ordinary
operation uses 120 requests/minute. The isolated benchmark temporarily raises that
limit and restores it afterward.

```powershell
$env:JAVA_HOME = (Get-ChildItem .tools\jdk21 -Directory | Select-Object -First 1).FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -B -ntp clean verify
python scripts/scan-dependencies.py
python scripts/test_database_tools.py
docker compose up --build -d --wait --wait-timeout 300
$env:BASE_URL = 'http://localhost:8088'
python scripts/demo.py
python scripts/check-observability.py
python scripts/restore-drill.py
```

The local `.tools` JDK is ignored. A fresh clone can use installed JDK 21/Maven
3.9+; Docker builds its own toolchain. The [OpenAPI export](api/openapi.json) records
this local server URL; `/v3/api-docs` generates the contract. Testcontainers uses
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
