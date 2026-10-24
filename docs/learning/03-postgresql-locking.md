# PostgreSQL locking

## Concept in plain English

A row lock forces competing writers to wait. Isolation controls which committed versions a query may see; READ COMMITTED alone does not serialize a read-check-write sequence.

## Why backend engineers need it

Two individually valid withdrawals can jointly violate an available-funds rule.

## LedgerCore implementation

AccountStore.lock sorts UUIDs and locks each row in a separate query. TransferEngine validates the freshly locked projection. Lock timeouts have bounded whole-transaction retries.

## Relevant files and classes

`accounts/AccountStore.java`, `transfers/application/TransferEngine.java`, `LedgerCoreIT.repeatedConcurrentWithdrawalsCannotOverspend`, `oppositeDirectionTransfersDoNotDeadlock`.

## Concrete example

Timeline: T1 locks source=1000; T2 waits; T1 posts 800 and commits balance=200; T2 acquires the row, sees 200 and records FAILED without a journal.

## Common mistakes

Locking only a destination, checking funds before the lock, inconsistent A→B/B→A lock order, or using Thread.sleep as coordination can hide races.

## Failure scenario

A lock timeout aborts that attempt. A fresh transaction re-reserves the key. Sorted acquisition prevents the classic two-account cycle, but future SQL paths can still introduce deadlocks.

## Interview question and answer

Why not optimistic locking? It is valid but creates retries on hot accounts. Pessimistic locking matches the short serialized funds decision; neither strategy fixes a missing invariant.

## Further learning exercise

Use two psql sessions to hold FOR UPDATE and inspect pg_blocking_pids. Draw lost update, phantom and write-skew examples; explain which rows their solution must lock.
