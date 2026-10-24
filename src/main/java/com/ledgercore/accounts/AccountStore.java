package com.ledgercore.accounts;

import com.ledgercore.common.Actor;
import com.ledgercore.common.DomainException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AccountStore {
  public record Account(
      UUID id,
      UUID ownerId,
      UUID ledgerAccountId,
      String currency,
      String status,
      long balanceMinor,
      Instant createdAt) {}

  private final JdbcTemplate db;

  public AccountStore(JdbcTemplate db) {
    this.db = db;
  }

  private static Account map(ResultSet r, int n) throws SQLException {
    return new Account(
        r.getObject("id", UUID.class),
        r.getObject("owner_id", UUID.class),
        r.getObject("ledger_account_id", UUID.class),
        r.getString("currency"),
        r.getString("status"),
        r.getLong("balance_minor"),
        r.getTimestamp("created_at").toInstant());
  }

  public static final RowMapper<Account> MAPPER = AccountStore::map;

  public Account get(UUID id) {
    return db.query("SELECT * FROM financial_accounts WHERE id=?", MAPPER, id).stream()
        .findFirst()
        .orElseThrow(() -> new DomainException(404, "ACCOUNT_NOT_FOUND", "Account does not exist"));
  }

  public Account owned(Actor actor, UUID id) {
    Account a = get(id);
    if (!actor.admin() && !a.ownerId().equals(actor.id()))
      throw new DomainException(404, "ACCOUNT_NOT_FOUND", "Account does not exist");
    return a;
  }

  public List<Account> lock(List<UUID> ids) {
    List<UUID> sorted = ids.stream().distinct().sorted().toList();
    // Separate statements make acquisition order explicit, independent of query planner choices.
    return sorted.stream()
        .map(
            id ->
                db
                    .query("SELECT * FROM financial_accounts WHERE id=? FOR UPDATE", MAPPER, id)
                    .stream()
                    .findFirst()
                    .orElseThrow(
                        () ->
                            new DomainException(
                                404, "ACCOUNT_NOT_FOUND", "Account does not exist")))
        .toList();
  }
}
