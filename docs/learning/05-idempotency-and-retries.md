# Idempotency, unknown outcomes, and bounded retries

## Concept and purpose

A key identifies one requested operation, including its original outcome. A retry
asks for that outcome again. A deliberate new attempt uses a new key. This
separation matters because a network timeout cannot reveal whether the database
committed before the response was lost.

## The reservation is part of posting

`Idempotency.execute` requires an active database transaction and reserves a row
using `INSERT ... ON CONFLICT ... DO NOTHING`. Uniqueness is scoped to principal,
operation, and key. Transfer, simulated-deposit, and reversal namespaces differ;
a reversal operation also includes the original transfer ID.

The SHA-256 fingerprint covers canonical kind, participants, minor units,
currency, and original reference. Thus `100.0` and `100.00` identify the same
request, while a changed destination or amount produces `409 IDEMPOTENCY_CONFLICT`.
A raw JSON hash would incorrectly distinguish equivalent whitespace or amount
spellings and might depend on property order.

| Step | Request A | Concurrent duplicate B |
|---|---|---|
| 1 | inserts PROCESSING reservation | attempts same unique insert and waits |
| 2 | locks accounts and posts | cannot independently create a second reservation |
| 3a | finishes response and COMMIT | observes committed conflict, loads response |
| 3b | alternatively crashes and ROLLBACK | insertion can proceed after failed reservation disappears |

The pending row is never committed in a separate transaction. Deferred checks
forbid unfinished committed operations. V2 also requires one transfer per
financial replay record and validates its stored status/body against the outcome.
Finished fingerprints, identities, and response data cannot be rewritten or
deleted. `expires_at` can change as metadata without releasing the key; there is
currently no cleanup that permits reuse.

## Responses distinguish rejection from uncertainty

| Scenario | Retained key/result | Client action |
|---|---|---|
| Invalid request or unauthorized source | rolled back/not consumed | correct request; a valid later call may use the key |
| Insufficient funds | retained 422 and failed transfer | retry replays rejection; use new key after intentionally funding/retrying |
| Destination amount overflow | retained 422 business rejection | investigate limit; repeating key remains same rejection |
| Failure before commit | no committed partial reservation or posting | repeat original key |
| Failure after commit before response | complete durable outcome exists | repeat original key or query known ID |
| Contention exhausted | 503 asking for same key | back off and repeat original key |

The engine retries only explicit concurrency failures in a fresh transaction.
An automatic retry of every exception can conceal a broken invariant or repeat
an unrelated side effect. Internal retries retain the same request identity;
they do not create a new key per attempt.

## Experiments and common mistakes

`LedgerCoreIT` tests eight concurrent duplicates, canonical amounts, retained
insufficient-funds responses, informational expiry, validation that does not
consume a key, and postcommit response loss. `FinancialIntegrityIT` races changed
payloads under one key and exercises both identical and distinct reversal keys.
`ProcessRecoveryIT` repeats the guarantee across real child-JVM termination and
fresh-process replay. Inspect reports before claiming those tests ran locally.

Avoid SELECT-then-INSERT reservation, Redis-only deduplication, deleting expired
keys, allowing a caller to mutate its old response, and retrying with a random
new key after every timeout. Storing credentials or bearer headers in replay
metadata is unnecessary and unsafe; only the replayable business response is kept.

## Questions and exercises

- **Why can concurrent duplicates safely wait?** The unique constraint arbitrates
  reservations, and PostgreSQL waits for the competing insert's transaction.
- **Why is a response fingerprint not enough?** Durable uniqueness must arbitrate
  the requests before either independently posts; hashing alone has no locking.
- **Why return the old rejection after funds arrive?** Changing an old request's
  meaning would make retries time-dependent and could create unexpected payments.
- **What if archival removes a seven-year-old result?** A retained tombstone must
  still prevent accidental key reuse and provide a documented replay policy.

Run `mvn '-Dit.test=LedgerCoreIT,FinancialIntegrityIT' verify`. Draw both branches
of the reservation timeline. Design an archival record that preserves principal,
operation, key, fingerprint, and outcome identity without retaining sensitive
HTTP headers. Explain how an unknown response differs from `FAILED` in the API.
