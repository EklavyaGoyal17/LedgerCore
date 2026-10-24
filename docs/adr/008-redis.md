# ADR 008: Keep Redis outside financial correctness

Status: accepted.

## Context

Rate limiting benefits from a fast shared counter, while financial state must survive cache loss.

## Decision

Use atomic INCR+EXPIRE Lua for fixed 60-second windows. Authentication fails closed during Redis failure; authenticated money operations fail open.

## Alternatives

Redis balances or financial idempotency keys would couple correctness to eviction, replication and TTL behavior. A local-only limiter would not coordinate multiple app instances.

## Consequences and verification

Redis outage restricts login but does not invalidate existing funds. Authenticated abuse controls weaken during outage; ingress controls remain a deployment responsibility.

Read the corresponding implementation and tests before changing this decision. `docs/VERIFICATION.md` records actual execution evidence.
