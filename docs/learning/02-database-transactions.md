# Database transactions

## Concept in plain English

A transaction is an atomic group of database changes. ACID means atomicity, consistency, isolation and durability under the configured storage guarantees.

## Why backend engineers need it

A financial response must correspond to a complete durable operation, never half a debit.

## LedgerCore implementation

Journal entries, account projections, terminal transfer, replay response, audit and outbox share one transaction. Deferred triggers inspect the final state at commit.

## Relevant files and classes

`TransferEngine.execute`, `JournalPosting.post`, `Idempotency.execute`, `db/migration/V1__financial_core.sql`.

## Concrete example

A crash after inserting the first journal entry rolls back every write. A crash after commit preserves every write even if the client receives no response.

## Common mistakes

Autocommit per statement, catching an SQL error and continuing an aborted transaction, or making Kafka part of transfer success breaks the boundary.

## Failure scenario

LedgerCoreIT.precommitFailuresRollbackEveryFinancialEffect injects failures at five boundaries. responseLossAfterCommitReplaysOriginalOutcome tests the opposite side of commit.

## Interview question and answer

Does an HTTP 500 mean no money moved? No. A response may fail after commit; use the original key to determine the committed result.

## Further learning exercise

Run each boundary test. In psql compare BEGIN/ROLLBACK with BEGIN/COMMIT and inspect visibility from a second connection.
