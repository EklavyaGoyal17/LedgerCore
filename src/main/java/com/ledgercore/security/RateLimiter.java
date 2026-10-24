package com.ledgercore.security;

import com.ledgercore.transfers.application.Idempotency;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {
  private static final DefaultRedisScript<Long> SCRIPT =
      new DefaultRedisScript<>(
          "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n",
          Long.class);
  private final StringRedisTemplate redis;
  private final MeterRegistry metrics;
  private final int limit;

  public RateLimiter(
      StringRedisTemplate redis,
      MeterRegistry metrics,
      @Value("${ledgercore.rate-limit.requests-per-minute}") int limit) {
    this.redis = redis;
    this.metrics = metrics;
    this.limit = limit;
  }

  public boolean allow(String identity, boolean authentication) {
    try {
      Long count =
          redis.execute(SCRIPT, List.of("ledgercore:rate:" + Idempotency.fingerprint(identity)));
      return count != null && count <= (authentication ? Math.min(20, limit) : limit);
    } catch (org.springframework.dao.DataAccessException ex) {
      metrics
          .counter(
              "ledgercore.rate_limit.unavailable", "policy", authentication ? "closed" : "open")
          .increment();
      // Authentication fails closed. Authenticated business traffic fails open; DB invariants
      // remain authoritative.
      return !authentication;
    }
  }
}
