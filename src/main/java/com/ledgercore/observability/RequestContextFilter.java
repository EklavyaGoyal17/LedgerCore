package com.ledgercore.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component("ledgerCoreRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(RequestContextFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String supplied = req.getHeader("X-Correlation-ID");
    String id =
        supplied != null && supplied.matches("[A-Za-z0-9._-]{1,64}")
            ? supplied
            : UUID.randomUUID().toString();
    long start = System.nanoTime();
    try {
      MDC.put("correlationId", id);
      MDC.put("requestId", UUID.randomUUID().toString());
      res.setHeader("X-Correlation-ID", id);
      chain.doFilter(req, res);
    } finally {
      log.atInfo()
          .addKeyValue("operation", req.getMethod())
          .addKeyValue("status", res.getStatus())
          .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
          .log("HTTP request completed");
      MDC.remove("correlationId");
      MDC.remove("requestId");
    }
  }
}
