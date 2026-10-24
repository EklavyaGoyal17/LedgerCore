# Idempotency and retries

## Concept in plain English

An idempotency key identifies one requested operation. A retry repeats its outcome; a new attempt uses a new key.

## Why backend engineers need it

Network failure cannot tell a client whether commit happened. Retrying without durable deduplication can move money twice.

## LedgerCore implementation

Idempotency.execute reserves a unique principal/operation/key using INSERT ON CONFLICT, compares SHA-256 of canonical values and persists status/body with the financial transaction.

## Relevant files and classes

`transfers/application/Idempotency.java`, `TransferEngine.execute`, duplicate/expiry/response-loss tests in LedgerCoreIT.

## Concrete example

Eight simultaneous calls share one key. The winning insert posts once. Competitors wait, observe the stored result and receive the same transfer ID. 100.0 and 100.00 canonicalize identically.

## Common mistakes

A SELECT-then-INSERT race, Redis-only storage, random new keys on every timeout, or deleting old keys after seven days can duplicate payments.

## Failure scenario

A process failure before commit removes both reservation and posting; retry is safe. A failure after commit retains both. A durable insufficient-funds outcome still replays after later funding.

## Interview question and answer

What happens to PROCESSING after a crash? It was never committed separately, so PostgreSQL rollback removes it. A deferred trigger forbids unfinished committed records.

## Further learning exercise

Run duplicateRequestsIncludingCanonicalAmountsPostOnlyOnce and expiryIsInformationalAndNeverReleasesAUsedKey. Design a tombstone archival scheme that preserves uniqueness forever.
