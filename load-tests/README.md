# Reproducible workloads

Run the demo first, then use Swagger to log in and provision dedicated funded load accounts.
`TOKEN` must own `SOURCE`; use the same currency for `DESTINATION`.
Set `RATE_LIMIT_PER_MINUTE` above planned load in a local benchmark environment and
restart backend; otherwise HTTP 429 measures throttling. Compose defaults to 120 requests
per minute per principal. Keep credentials and tokens out of saved results.

```sh
k6 run -e TOKEN="$TOKEN" -e SOURCE="$SOURCE" -e DESTINATION="$DESTINATION" \
  -e RUN_ID=experiment-001 -e MODE=mixed load-tests/workload.js
```

Modes: `accounts`, `balance`, `transfers`, `contention`, `mixed`. Transfer and contention
modes deliberately share a single source account; vary VUS (1, 5, 20) to observe row
lock serialization. Mixed sends two balance reads per transfer. `DURATION` and `VUS`
are configurable. Use a new RUN_ID each run to avoid replaying old requests. Reusing
a RUN_ID intentionally measures idempotent replay instead of posting throughput.
For distributed tests include a unique load-generator suffix in RUN_ID.

Record CPU, memory, Docker limits, storage, Java version, dataset size, p50/p95/p99,
request errors, Hikari active connections, lock waits and outbox backlog. Compare
reconciliation before and after. A 422 is an expected business result but should
be counted separately from successful postings. No benchmark numbers are assumed.
