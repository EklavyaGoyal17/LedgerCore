# ADR 003: Model customer money as liabilities

Status: accepted.

## Context

Subtracting one mutable balance and incrementing another provides no independent accounting history.

## Decision

Store balanced journals. Customer liability accounts are credit-normal; simulated settlement is an asset and debit-normal.

## Alternatives

A single balance table is easier to query but hides missing/duplicated counterpart movements. Event sourcing alone does not guarantee accounting balance.

## Consequences and verification

More rows and validation are necessary. The journal supports reconciliation and reversal. Balanced does not mean economically correct: transfer-shape checks are also required.

Read the corresponding implementation and tests before changing this decision. `docs/VERIFICATION.md` records actual execution evidence.
