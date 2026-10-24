# LedgerCore implementation and verification plan

The remote repository was empty when inspected on 23 October 2026. No existing work was replaced.

1. Build a Java 21 modular monolith and PostgreSQL migrations. Enforce immutable,
   balanced, same-currency journals and consistent nonnegative customer projections
   at database commit. Model customer funds as credit-normal liabilities and simulated
   funding as a debit-normal settlement asset.
2. Add atomic transfer, deposit and reversal use cases. Lock customer rows in UUID
   order; reserve durable idempotency keys in the same transaction as the outcome.
3. Add BCrypt identities, signed expiring JWTs, ownership checks, validation and
   Problem Details. Bootstrap an optional administrator using environment configuration.
4. Add transactional outbox, acknowledged Kafka publishing, durable consumer deduplication,
   backoff, Redis rate limiting, independent reconciliation and operational telemetry.
5. Verify using real PostgreSQL, Kafka and Redis Testcontainers, deterministic fault
   injection, repeated concurrent withdrawals and HTTP security tests.
6. Deliver Docker Compose, CI, k6 workloads, executable demo, ADRs, learning guides,
   architecture, failure runbook, API examples and evidence checklist.

Evidence and remaining limitations are tracked in `docs/VERIFICATION.md`. No
benchmark, CI or infrastructure success will be claimed without executing it.
