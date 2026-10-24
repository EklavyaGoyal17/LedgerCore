package com.ledgercore.security;

import com.ledgercore.common.Json;
import com.ledgercore.common.ProblemAdvice;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
  private final RateLimiter limiter;
  private final Json json;
  private final boolean enabled;

  public RateLimitFilter(
      RateLimiter limiter, Json json, @Value("${ledgercore.rate-limit.enabled}") boolean enabled) {
    this.limiter = limiter;
    this.json = json;
    this.enabled = enabled;
  }

  @Bean
  FilterRegistrationBean<RateLimitFilter> disableServletRegistration(RateLimitFilter filter) {
    FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (enabled && req.getRequestURI().startsWith("/api/")) {
      boolean auth = req.getRequestURI().startsWith("/api/v1/auth/");
      var principal = SecurityContextHolder.getContext().getAuthentication();
      String identity =
          auth || principal == null ? "ip:" + req.getRemoteAddr() : "user:" + principal.getName();
      if (!limiter.allow(identity, auth)) {
        res.setStatus(429);
        res.setHeader("Retry-After", "60");
        res.setContentType("application/problem+json");
        res.getWriter()
            .write(
                json.write(
                    ProblemAdvice.body(
                        429,
                        "RATE_LIMITED",
                        "Request limit reached or authentication rate limiter unavailable")));
        return;
      }
    }
    chain.doFilter(req, res);
  }
}
