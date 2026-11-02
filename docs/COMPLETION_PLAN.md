# Completion and hardening execution plan

This pass starts from the verified initial commit `8eb6933`, with 44 passing tests.
The owner requested full completion, parallel agents, improvements and continued
commit/push delivery. Work is split by file ownership to avoid conflicting edits.

1. **Financial integrity:** independently review the core; enforce durable transfer
   evidence and replay immutability in PostgreSQL; test overflow, replay, reversal,
   lock retry and controlled reconciliation discrepancies.
2. **Events:** verify actual broker stop/start; recover from failed publication and
   duplicate processing; provide durable poison-event quarantine, administrative
   inspection and audited replay without changing financial effects.
3. **Deployment and real crash recovery:** separate Flyway/runtime DB identities,
   deny runtime DDL/TRUNCATE/financial-history edits, run an actual child JVM crash
   and restart, implement binary-safe backup/restore and execute an isolated restore drill.
4. **Root integration:** add meaningful static analysis and architecture rules, true
   property tests, a dependency SBOM and vulnerability scan, reproducible sustained
   load experiments and measured reports. Review and integrate all changes.
5. **Delivery:** reconcile every master-prompt requirement with a code/test/document
   reference, refresh the API export and learning material, run clean verification
   and Compose/demo/operational checks, commit/push and inspect CI for the delivered SHA.

Scope stays a simulated modular-monolith backend. No external money rails or
unrequested microservices, Kubernetes, FX or chargebacks are introduced. Results
and limitations must distinguish executed evidence from deployment assumptions.

## Completion record â€” 2 November 2026

All implementation workstreams above are complete. The hardened source commit
`a657c40` passed its push-triggered GitHub workflow, including real infrastructure
tests, Docker build/Java 21 runtime verification and the resolved dependency scan.
The fresh native build passed 85 tests with 89.63% instruction coverage. Five
sustained workloads passed 16,283 requests with clean reconciliation; all 9,047
outbox events subsequently reached durable consumer projection. The post-load
logical restore drill and actual statement query-plan inspection passed.

The final evidence commit publishes the measured results, source-CI link and
operational limits, then receives its own GitHub verification run. See
[VERIFICATION.md](VERIFICATION.md) and the
[requirement map](MASTER_PROMPT_CHECKLIST.md) for the maintained handoff.
