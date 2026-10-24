# Viva and code walkthrough

## Concept in plain English

A code walkthrough explains a real execution path and the evidence behind its guarantees, instead of merely naming frameworks.

## Why backend engineers need it

An interviewer should be able to select a failure boundary and see exactly which state survives.

## LedgerCore implementation

Start with Money and Journal, read the migration checks, follow TransferController into TransferEngine and Idempotency, then inspect tests, event delivery and reconciliation.

## Relevant files and classes

`ledger/domain`, `db/migration/V1__financial_core.sql`, `transfers/application`, `src/test/java/com/ledgercore`, `scripts/demo.py`.

## Concrete example

Run the demo, select its completed transfer ID, inspect statement journal references and the matching outbox envelope. Repeat the same request key and show the same transfer ID.

## Common mistakes

Reciting generic Spring definitions without pointing to lock SQL or claiming a test ran without its report makes the explanation unconvincing.

## Failure scenario

If Docker is unavailable, separate domain-test evidence from unexecuted PostgreSQL guarantees. Never substitute H2 and describe it as equivalent concurrency proof.

## Interview question and answer

What is the strongest evidence for no overspending? Repeated real PostgreSQL races plus application lock reasoning and database constraints; tests alone cannot prove all possible deployments correct.

## Further learning exercise

Present one implementation trade-off from each ADR. Reproduce a rollback and a postcommit lost response. Explain one remaining limitation without understating the verified core.
