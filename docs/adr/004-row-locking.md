# ADR 004: Lock account projections in deterministic order

Status: accepted.

## Context

Two different destinations can race to spend the same source funds.

## Decision

At READ COMMITTED lock every customer account in Java UUID order using individual FOR UPDATE statements, then validate funds and post.

## Alternatives

Optimistic versions cause repeated conflicts on hot accounts. SERIALIZABLE is viable but requires generalized retry handling. Redis locks would add lease and partition hazards.

## Consequences and verification

Hot accounts serialize. Keep transactions bounded, avoid broker calls while holding financial account locks, and retry lock acquisition failures only around a fresh whole transaction.

Read the corresponding implementation and tests before changing this decision. `docs/VERIFICATION.md` records actual execution evidence.
