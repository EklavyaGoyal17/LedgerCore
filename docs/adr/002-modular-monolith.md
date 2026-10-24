# ADR 002: Start with a modular monolith

Status: accepted.

## Context

The ledger, business outcome, audit and outbox must commit together.

## Decision

Organize packages by business capability and deploy one Spring Boot process. Use JPA for identities and explicit JDBC for lock-sensitive financial SQL.

## Alternatives

Microservices would introduce distributed commit and recovery complexity before there is an independent scaling or ownership need.

## Consequences and verification

Package boundaries require discipline. Future extraction must preserve idempotency and accounting boundaries rather than simply moving classes.

Read the corresponding implementation and tests before changing this decision. `docs/VERIFICATION.md` records actual execution evidence.
