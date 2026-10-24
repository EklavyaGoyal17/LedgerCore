package com.ledgercore.common;

import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ProblemAdvice {
  private static final Logger log = LoggerFactory.getLogger(ProblemAdvice.class);

  public static Map<String, Object> body(int status, String code, String detail) {
    return Map.of(
        "type",
        "urn:ledgercore:problem:" + code.toLowerCase(java.util.Locale.ROOT),
        "title",
        code,
        "status",
        status,
        "detail",
        detail,
        "code",
        code);
  }

  @ExceptionHandler(DomainException.class)
  ResponseEntity<ProblemDetail> domain(DomainException ex) {
    return problem(ex.status(), ex.code(), ex.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    HandlerMethodValidationException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class
  })
  ResponseEntity<ProblemDetail> validation(Exception ex) {
    return problem(400, "INVALID_REQUEST", "Request fields, headers or parameters are invalid");
  }

  @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
  ResponseEntity<ProblemDetail> forbidden(Exception ex) {
    return problem(403, "FORBIDDEN", "Access is forbidden");
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ProblemDetail> missing(Exception ex) {
    return problem(404, "NOT_FOUND", "Resource does not exist");
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ProblemDetail> database(DataAccessException ex) {
    log.error("Database operation failed; errorCategory={}", ex.getClass().getSimpleName());
    return problem(
        503,
        "DATABASE_UNAVAILABLE",
        "Operation could not be confirmed; retry with the same idempotency key");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ProblemDetail> unexpected(Exception ex) {
    log.error("Unhandled operation failure; errorCategory={}", ex.getClass().getSimpleName());
    return problem(
        500,
        "INTERNAL_ERROR",
        "Operation could not be confirmed; query its status or retry with the same key");
  }

  private ResponseEntity<ProblemDetail> problem(int status, String code, String message) {
    ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), message);
    p.setType(URI.create("urn:ledgercore:problem:" + code.toLowerCase(java.util.Locale.ROOT)));
    p.setTitle(code);
    p.setProperty("code", code);
    p.setProperty("correlationId", MDC.get("correlationId"));
    return ResponseEntity.status(status).body(p);
  }
}
