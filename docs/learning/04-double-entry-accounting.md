# Double-entry accounting

## Concept in plain English

Every financial movement records equal debits and credits across accounting accounts. Debit and credit describe direction, not inherently good/bad or plus/minus.

## Why backend engineers need it

Independent counterpart entries reveal missing sides and allow a durable explanation of each customer balance.

## LedgerCore implementation

Customer funds are credit-normal liabilities. Funding debits a debit-normal settlement asset. Journals are immutable and validated both in Java and by deferred PostgreSQL triggers.

## Relevant files and classes

`ledger/domain/Journal.java`, `ledger/JournalPosting.java`, `accounts/AccountService.balance`, `V1__financial_core.sql`.

## Concrete example

A 500 INR transfer debits A liability 50000 and credits B liability 50000. A decreases while B increases. A reversal swaps these directions in a new journal.

## Common mistakes

Treating debit as always increasing balance, using double for money, balancing across mixed currencies, or correcting old entries destroys useful accounting guarantees.

## Failure scenario

A balanced journal can still debit the wrong account. verify_transfer checks exact source/destination/amount shape, and reconciliation independently repeats that comparison.

## Interview question and answer

Does double entry prove the transfer was authorized? No. Accounting balance, economic intent and authorization are separate invariants.

## Further learning exercise

Add a three-entry balanced journal domain test. Compute customer liabilities before and after an internal transfer and after a simulated deposit.
