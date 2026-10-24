package com.ledgercore.transfers.application;

import com.ledgercore.common.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class Idempotency {
  public record Result(int status, String body) {}

  private final JdbcTemplate db;

  public Idempotency(JdbcTemplate db) {
    this.db = db;
  }

  /** ON CONFLICT waits for the competing insert's transaction before observing its result. */
  public Result execute(
      UUID principal,
      String operation,
      String key,
      String canonical,
      Function<UUID, Result> action) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Idempotency requires a transaction");
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
      throw new DomainException(
          400,
          "INVALID_IDEMPOTENCY_KEY",
          "Supply an Idempotency-Key of 1–128 ASCII letters, digits, dots, underscores, colons or hyphens");
    }
    String fingerprint = fingerprint(canonical);
    UUID id = UUID.randomUUID();
    int inserted =
        db.update(
            "INSERT INTO idempotency_records(id,principal_id,operation,key,fingerprint,state) VALUES (?,?,?,?,?,'PROCESSING') ON CONFLICT (principal_id,operation,key) DO NOTHING",
            id,
            principal,
            operation,
            key,
            fingerprint);
    if (inserted == 0) {
      return db.queryForObject(
          "SELECT fingerprint,response_status,response_body::text FROM idempotency_records WHERE principal_id=? AND operation=? AND key=? FOR UPDATE",
          (rs, n) -> {
            if (!fingerprint.equals(rs.getString(1)))
              throw new DomainException(
                  409,
                  "IDEMPOTENCY_CONFLICT",
                  "This key was already used with a different request");
            return new Result(rs.getInt(2), rs.getString(3));
          },
          principal,
          operation,
          key);
    }
    Result result = action.apply(id);
    db.update(
        "UPDATE idempotency_records SET state='FINISHED',response_status=?,response_body=?::jsonb WHERE id=?",
        result.status(),
        result.body(),
        id);
    return result;
  }

  public static String fingerprint(String canonical) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
