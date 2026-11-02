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

Modes: `accounts`, `balance`, `transfers`, `contention`, `mixed`. With the single-pair
environment variables shown above, transfer and contention traffic share that
source account; vary VUS (1, 5, 20) to observe row lock serialization. The fixture
runner below separates ordinary transfer pairs per VU. Mixed sends two balance
reads per transfer. `DURATION` and `VUS`
are configurable. Use a new RUN_ID each run to avoid replaying old requests. Reusing
a RUN_ID intentionally measures idempotent replay instead of posting throughput.
For distributed tests include a unique load-generator suffix in RUN_ID.

Record CPU, memory, Docker limits, storage, Java version, dataset size, p50/p95/p99,
request errors, Hikari active connections, lock waits and outbox backlog. Compare
reconciliation before and after. A 422 is an expected business result but should
be counted separately from successful postings. No benchmark numbers are assumed.

## Sustained reproducible experiment

The runner provisions a dedicated simulated identity and separate funded account
pairs, runs all five modes, exports k6 summaries and reconciles before/after:

```sh
python scripts/load-experiment.py --base-url http://localhost:8088 \
  --duration 60s --vus 5 --contention-vus 20
```

Set `RATE_LIMIT_PER_MINUTE=100000` in the ignored local `.env` and recreate backend
before this controlled experiment. Restore the normal 120 limit afterward. Do not
raise the admission limit on a public deployment solely to make a benchmark pass.
`ADMIN_EMAIL`/`ADMIN_PASSWORD` come from the process environment or explicitly
documented local simulation defaults. Tokens live only in a temporary read-only
mount and are deleted after execution; output contains no tokens/passwords.

Account/balance/ordinary-transfer/mixed modes use separate source pairs per VU.
Contention mode deliberately makes every VU use the first source pair, so it
measures serialization on one account. The reports record Docker CPU/memory
allocation, mode duration, VU count, dataset reconciliation, unique run IDs and
raw p50/p95/p99 summaries. The code distinguishes successful postings from
insufficient-funds responses; HTTP422 is an expected business outcome, while
unexpected HTTP failures and failed checks fail the experiment.

These local measurements depend on hardware, growing history and background work.
They are evidence for this configuration, not production capacity or a universal
latency guarantee. Run larger experiments on isolated hardware before setting SLOs.
