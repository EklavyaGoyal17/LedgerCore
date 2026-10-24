package com.ledgercore.transfers;

import com.ledgercore.common.Actor;
import com.ledgercore.common.DomainException;
import com.ledgercore.ledger.domain.Money;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransferStore {
  public record Transfer(
      UUID id,
      UUID actorId,
      UUID sourceAccountId,
      UUID destinationAccountId,
      String amount,
      String currency,
      String kind,
      String status,
      String failureCode,
      UUID journalId,
      UUID originalTransferId,
      Instant createdAt) {}

  private final JdbcTemplate db;

  public TransferStore(JdbcTemplate db) {
    this.db = db;
  }

  public Transfer get(UUID id, boolean lock) {
    return db
        .query(
            "SELECT * FROM transfers WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            (r, n) ->
                new Transfer(
                    r.getObject("id", UUID.class),
                    r.getObject("actor_id", UUID.class),
                    r.getObject("source_id", UUID.class),
                    r.getObject("destination_id", UUID.class),
                    Money.format(r.getLong("amount_minor")),
                    r.getString("currency"),
                    r.getString("kind"),
                    r.getString("status"),
                    r.getString("failure_code"),
                    r.getObject("journal_id", UUID.class),
                    r.getObject("original_transfer_id", UUID.class),
                    r.getTimestamp("created_at").toInstant()),
            id)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new DomainException(404, "TRANSFER_NOT_FOUND", "Transfer does not exist"));
  }

  public Transfer owned(Actor actor, UUID id) {
    Transfer t = get(id, false);
    Long visible =
        db.queryForObject(
            "SELECT count(*) FROM financial_accounts WHERE owner_id=? AND (id=? OR id=?)",
            Long.class,
            actor.id(),
            t.sourceAccountId(),
            t.destinationAccountId());
    if (!actor.admin() && !t.actorId().equals(actor.id()) && visible == 0)
      throw new DomainException(404, "TRANSFER_NOT_FOUND", "Transfer does not exist");
    return t;
  }
}
