# Redis and non-authoritative state

## Concept in plain English

Redis is a fast shared ephemeral store. Eviction, TTL and outages are normal design inputs for cache-like data.

## Why backend engineers need it

Fast infrastructure must not become an accidental authority for durable financial correctness.

## LedgerCore implementation

RateLimiter runs one Lua script to increment a counter and set its 60-second expiry atomically. Identity strings are hashed before becoming Redis keys. No balances or idempotency results are cached.

## Relevant files and classes

`security/RateLimiter.java`, `RateLimitFilter.java`, `EventInfrastructureIT.redisUsesAtomicExpiryAndDocumentedOutagePolicies`.

## Concrete example

With test limit 3, calls 1–3 are admitted and 4 is rejected. The Redis TTL remains between 1 and 60 seconds. During outage, existing business calls are admitted and auth calls are denied.

## Common mistakes

Separate INCR then EXPIRE can leave permanent counters after a crash. Trusting cached balances permits stale-read overspending. Blindly failing open for login weakens brute-force defenses.

## Failure scenario

Redis pause triggers connection/command timeouts. Micrometer counts unavailable policies; PostgreSQL still owns transfer invariants. There is no financial cache invalidation problem because none exists.

## Interview question and answer

Why fixed window? It is transparent and atomic with a small script, but permits boundary bursts. A sliding window or token bucket offers smoother traffic at higher complexity.

## Further learning exercise

Measure Redis timeout impact. Design a metadata-only cache with TTL and invalidation on account status changes, keeping ownership decisions authoritative.
