# Master-prompt implementation and evidence map

This checklist maps the supplied specification to the implemented repository.
Execution totals, delivered commit and measured results live in `VERIFICATION.md`
and `verification/`; a source reference alone is not a claim that a test passed.

| Prompt area | Implementation | Evidence / explanation |
|---|---|---|
| 1. Simulated financial product | register/login, customer accounts, deposits, transfers, statements, reversals and reconciliation | README simulation label; API demo; no external payment rails or credentials |
| 2. Java/Spring/PostgreSQL stack | Java21, Boot MVC/JPA/Security/Validation/Actuator, Maven, Flyway, Redis, Kafka | pinned POM/Compose, runtime CycloneDX SBOM and dependency policy |
| 3. Modular monolith | business-capability packages, controllers/DTOs/domain/coordinators/adapters | ArchitectureTest verifies acyclic modules, domain framework independence and no Redis/Kafka dependency in financial posting |
| 4. Domain model | users, financial/ledger accounts, journals/entries, transfers, idempotency, audit/outbox | V1/V2/V3 migrations, foreign/unique/check constraints and indexes |
| 5A. Balanced journals | exact minor units; Java journal checks; deferred DB balance checks | FinancialDomainTest, jqwik properties, direct SQL guard cases |
| 5B. Atomic transfers | single local transaction includes outcome, journal, projection, replay, audit and event | fault boundary tests and genuine precommit JVM termination |
| 5C. No overdraft | account row locks, nonnegative projection and deferred ledger equality | repeated simultaneous800/1000 scenario, opposing transfers and generated DB sequences |
| 5D. Durable idempotency | principal/operation/key uniqueness, canonical fingerprint and retained result | eight-caller duplicate race, conflict race, response loss and fresh-JVM replay |
| 5E. Durable posted entries | immutable journals/entries, original-transaction-only append | SQL mutation/append tests, restricted runtime capabilities |
| 5F. Currency/precision | INR/USD, fixed two-decimal exact conversion, bounded operation sizes | Money/jqwik tests; DB currency, overflow and mismatch cases |
| 5G. Auditability | actor/action/resource/correlation records in financial commit | V2 requires correct audit evidence; immutable replay history for event operations |
| 5H. Consistent state | transfer/journal/projection/replay/event identity checked at commit | V2 deferred evidence checks and independent reconciliation |
| 6. Double-entry accounting | credit-normal customer liabilities, debit-normal simulated settlement | deposit/transfer/reversal tests, complete journal read API, demo journal arithmetic |
| 7. Real concurrency | READ COMMITTED with sorted individual row locks, narrow whole-transaction retry | real55P03 lock timeout and externally induced40P01 deadlock, repeated races; isolation timelines in learning guides |
| 8. Transfer state machine | CREATED -> PROCESSING -> COMPLETED/FAILED; only terminal DB states | TransferLifecycle tests and technical-vs-business failure documentation |
| 9. Idempotency retention | no separate committed PROCESSING; stored success/insufficient/overflow results | immutable finished records; past expiry-horizon test; indefinite reservation policy |
| 10. Versioned REST API | validated requests, pagination, Problem Details, correlation IDs | generated OpenAPI + Swagger, schema/security/media/method integration assertions |
| 11. Authentication/security | BCrypt12, signed15-minute JWT, issuer/audience/expiry/current-role/status checks | real login, tampering, missing claims, ownership and administrative route tests |
| 12. Redis | atomic rate-counter increment/expiry; auth fails closed, authenticated business fails open | actual Redis outage/TTL test; no financial truth cached |
| 13. Kafka/outbox | transactional insertion, acknowledged publication, backoff and durable consumer deduplication | real broker stop/start, ack loss, consumer rollback/redelivery, poison quarantine and audited replay |
| 14. Reconciliation | coherent snapshot checks balance, business shape, currencies, reversals, projections, full replay/audit/event identities | independent corruption fixtures; persisted reports; no repair endpoint |
| 15. Fault injection/recovery | no-op production hook; controlled test exceptions and child-JVM termination | before transaction, validation, partial journal, outbox, precommit/postcommit, publication and consumer boundaries |
| 16. Observability | structured JSON logs, safe IDs/categories, metrics/histograms, W3C traces, Grafana and alerts | check-observability.py verifies targets, provisioned panels and real parent/child trace graph |
| 17. Unit/integration/property/load tests | JUnit/AssertJ/Mockito tooling, real infrastructure, jqwik/shrinking and k6 | clean verify reports, property attempts/seeds, sustained experiment artifacts and reconciliation |
| 18. Database design/performance | Flyway, targeted indexes, explicit SQL, bounded pages, connection/lock timeouts | actual EXPLAIN ANALYZE BUFFERS output, no entity lazy-loading N+1 on financial queries |
| 19. Developer experience | Compose health/start ordering, isolated migration job, .env examples | fresh and populated migration tests, packaged API startup, Python standard-library cross-platform scripts |
| 20. CI/quality | SHA-pinned actions, minimal permissions, format/unit/integration/static/coverage/package/SBOM/OSV steps | delivered-SHA GitHub workflow result linked in verification; Dependabot updates |
| 21. Documentation/ADRs | README, architecture, security, API examples, failure and operational runbooks | architecture decisions explain alternatives/trade-offs; code-linked source map |
| 22. Educational guides | all14 requested guides and code walkthrough workbook | each covers concept, need, implementation/files, example, mistakes, failures, Q&A and exercises |
| 23A-D/G. Functional demonstrations | scripts/demo.py | successful/insufficient/duplicate/concurrent movement, complete journals/statements and reconciliation |
| 23E. Application failure demo | ProcessRecoveryIT + legacy AFTER_COMMIT test | true process termination, committed result recovered with original key |
| 23F. Broker outage demo | EventInfrastructureIT and operational runbook | retained-storage stop/start and eventual delivery without extra financial posting |
| 23H. Performance demo | load-experiment.py and workload.js | accounts/balances/transfers/contention/mixed runs, explicit fixtures/VUs/duration, measured latency/throughput and before/after reconciliation |
| 24. Engineering quality | exact money, immutable evidence, explicit transactions and dependency boundaries | no production fault endpoint or external-money claims; no fabricated benchmarks or exactly-once claim |
| 25-26. Workflow/completion | incremental inspection, implementation, real verification and correction | completion plan, current verification artifacts and clean-build/Compose/CI outcomes |
| 27. Engineering handoff | ENGINEERING_SUMMARY.md and README | executive/architecture/map/decisions/commands/evidence/limits/study/Q&A/extensions |
| 28-29. Build for understanding | executable experiments, timelines, meaningful failure fixtures | learning guides reference actual classes/tests; unresolved environmental assumptions are stated explicitly |

## Deliberate scope boundaries

The prompt explicitly excludes real payment rails and unnecessary distributed
infrastructure. This project therefore does not add real money, FX, card storage,
Kubernetes or microservices. Multi-region failover, physical/PITR backups, external
penetration testing, MFA and asymmetric key rotation are deployment enhancements,
not substituted for implemented ledger correctness. Local plaintext services and
simulation defaults must not be represented as a certified banking deployment.

Financial idempotency and journal/event evidence are retained indefinitely.
Archiving is intentionally not implemented by deleting keys or postings; a future
design must preserve uniqueness and an auditable authoritative history. Full-history
reconciliation and statement windows are documented scaling trade-offs with actual
query/load evidence rather than an unsupported claim of unlimited capacity.
