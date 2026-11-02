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
