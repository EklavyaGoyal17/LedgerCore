# ADR 005: Reserve request keys in the financial transaction

Status: accepted.

## Context

A response may vanish after commit; concurrent duplicate callers may arrive before a key is visible.

## Decision

INSERT ON CONFLICT DO NOTHING reserves unique principal/operation/key. Canonical minor-unit fingerprints distinguish requests; response and journal commit together.

## Alternatives

Redis keys can expire or be lost independently of financial posting. A preflight SELECT alone races. Committing PROCESSING separately introduces abandoned-work recovery.

## Consequences and verification

Competing inserts wait. No PROCESSING record may commit. Keys are retained indefinitely; seven-day expires_at is informational minimum retention, not automatic deletion.

Read the corresponding implementation and tests before changing this decision. `docs/VERIFICATION.md` records actual execution evidence.
