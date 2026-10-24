# ADR 007: Correct by compensation

Status: accepted.

## Context

Editing posted entries would invalidate audit evidence and historical statements.

## Decision

Reject ordinary UPDATE/DELETE and append to committed journals. Reversal is a new balanced journal tied to one completed original transfer.

## Alternatives

Soft delete still allows historical accounting to change. Rewriting an old entry makes the current balance look correct while destroying the explanation.

## Consequences and verification

A recipient who has spent funds cannot be overdrawn by reversal. Operational corrections need explicit policies. Migration-owner credentials can bypass guards and must be separated in production.

Read the corresponding implementation and tests before changing this decision. `docs/VERIFICATION.md` records actual execution evidence.
