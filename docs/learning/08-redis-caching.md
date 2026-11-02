# Redis, admission control, and non-authoritative state

## Concept and purpose

Redis is a shared, low-latency store whose TTLs, eviction, restart, or network
outage must have an explicit meaning. LedgerCore uses it for request admission,
not financial balances or request outcomes. A cache outage cannot change what
money was posted or which payment key was used.

## Read the actual implementation

`security/RateLimitFilter` applies to `/api/` requests. Authentication routes use
the remote address as their identity; authenticated business routes use the
principal. `RateLimiter` hashes the identity before placing it in a Redis key and
executes this fixed-window algorithm as one Lua script:

```text
increment counter
if counter was newly created: expire in 60 seconds
admit only while count <= applicable limit
```

The first request starts the window. Later increments do not extend it. The
authentication limit is capped at 20 per window; the business limit is configurable.
Hashing keeps raw identity strings out of key names but does not make them
cryptographically anonymous. Separate INCR and EXPIRE calls have a crash window:
the counter could be incremented and never expire. The Lua script removes that
window on Redis. It does not need an atomic transaction with PostgreSQL because
this counter is admission state rather than financial truth.

## Outage behavior is part of the feature

| Situation | Result | Why |
|---|---|---|
| Under limit | request proceeds | normal admission |
| Over limit | 429 and Retry-After: 60 | bounded abuse |
| Redis unavailable for authentication | denied with 429 | retain brute-force protection |
| Redis unavailable for existing authenticated business request | request proceeds | financial correctness remains in PostgreSQL |

`EventInfrastructureIT.redisUsesAtomicExpiryAndDocumentedOutagePolicies` uses real
Redis to inspect TTL and limits, pauses the container, and exercises these
policies. Connection/command timeouts bound dependency waiting; observe their
actual configuration in `application.yml` before attributing latency to the DB.
The unavailable-policy metric uses bounded `open` and `closed` labels.

## Cache invalidation exercise

Suppose you add an account-display metadata cache. A short TTL can bound stale
labels, but account status and ownership used for authorization should still be
authoritative. Invalidating after a successful commit avoids publishing an
uncommitted status change. Multiple application instances need shared invalidation
or documented bounded staleness. This is an exercise; LedgerCore currently has no
financial or account-metadata cache requiring this strategy.

## Questions, mistakes, and experiments

- **Why is a cached balance unsafe for spending?** A stale positive value can
  survive a committed withdrawal. The locked, checked projection decides instead.
- **Can counter expiry duplicate a payment?** No; durable key uniqueness is
  independent of Redis.
- **Why not always fail open?** Authentication would lose its shared brute-force
  defense during an outage.
- **What does a fixed window cost?** It is simple but permits boundary bursts.
  Sliding windows or token buckets trade complexity for smoother admission.

Do not trust arbitrary forwarded-IP headers. A real ingress must define trusted
proxies/client addresses and additional edge limits. Do not cache bearer tokens,
password hashes, spendable funds, or an unused-key decision in Redis.

Run the Redis test and inspect its metric during the container pause. Explain why
a transfer still needs PostgreSQL when Redis is healthy. Design a metadata-only
invalidation experiment proving rollback cannot publish uncommitted account state.
