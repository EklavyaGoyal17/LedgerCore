# API contract and examples

The live OpenAPI contract is `/v3/api-docs`, with Swagger UI at
`/swagger-ui/index.html`. Amounts are decimal **strings**, never floating-point
JSON numbers. `INR` and `USD` are supported, with at most two fractional digits.
The minimum amount is 0.01, maximum 10000000000.00. Self transfers and FX are rejected.

| Endpoint | Access | Success |
|---|---|---|
| POST `/api/v1/auth/register` | public, rate limited | 201 `{id}` |
| POST `/api/v1/auth/login` | public, rate limited | 200 `{accessToken,tokenType,expiresIn}` |
| POST `/api/v1/accounts` | authenticated | 201 account metadata |
| GET `/api/v1/accounts` | owner's accounts | 200 `{items,offset,limit}` |
| GET `/api/v1/accounts/{id}` | owner/admin | 200 metadata |
| GET `/api/v1/accounts/{id}/balance` | owner/admin | 200 `{accountId,currency,amount,source}` |
| GET `/api/v1/accounts/{id}/statement` | owner/admin | 200 entries with journal IDs, references, debit/credit/running balances |
| POST `/api/v1/transfers` | source owner + key | 201 completed transfer |
| GET `/api/v1/transfers/{id}` | participant/initiator/admin | 200 completed or failed transfer |
| POST `/api/v1/transfers/{id}/reversals` | admin + key | 201 compensating transfer |
| POST `/api/v1/admin/simulated-deposits` | admin + key | 201 simulated deposit |
| POST `/api/v1/admin/reconciliation-runs` | admin | 201 completed report |
| GET `/api/v1/admin/reconciliation-runs/{id}` | admin | 200 persisted report |

There is no 202: posting/reconciliation are synchronous. Account creation and
reconciliation are not idempotent financial posting operations and do not accept
an idempotency key. Retrying account creation may create another empty account.

Successful transfer example (identifiers/timestamps illustrative):

```json
{
  "id":"e9851468-07fd-4ab5-aa88-52d1a6bf2ffd",
  "actorId":"07483891-f744-49b9-bda5-d7d410191131",
  "sourceAccountId":"426ad2c7-167c-42eb-ac3b-715c306cfa09",
  "destinationAccountId":"720b6f75-0438-4f93-8717-4f796177c03c",
  "amount":"500.00","currency":"INR","kind":"TRANSFER",
  "status":"COMPLETED","failureCode":null,
  "journalId":"33f1baa0-824b-432b-87cb-ea128e15db83",
  "originalTransferId":null,"createdAt":"2026-10-23T18:30:00Z"
}
```

Insufficient funds returns HTTP 422 and `application/problem+json`:

```json
{"type":"urn:ledgercore:problem:insufficient_funds","title":"INSUFFICIENT_FUNDS","status":422,"code":"INSUFFICIENT_FUNDS","detail":"Available funds are insufficient","transferId":"e9851468-07fd-4ab5-aa88-52d1a6bf2ffd"}
```

Other errors: 400 invalid input/missing key, 401 missing/invalid token, 403
administrative route, 404 missing or inaccessible object, 409 key conflict or
already reversed, 429 request limit/unavailable authentication limiter, 500
unexpected outcome, 503 unavailable database or exhausted lock retry. Responses
avoid SQL and exception details. Logs use safe exception categories. The
`X-Correlation-ID` response header follows a validated supplied value or a generated
UUID. A replay returns a fresh transport correlation ID and the original persisted
body/status; JSON property order is not guaranteed.

Pagination defaults to 50 and caps at 200; offset is bounded at 100000.
Statements use a repeatable snapshot per request and deterministic entry ordering.
Separate pages are separate snapshots: new inserts can shift offsets. Cursor
pagination would improve continuity/large-history efficiency but is not required
for this educational dataset. Balances and statements are always authorized
before revealing private data.
