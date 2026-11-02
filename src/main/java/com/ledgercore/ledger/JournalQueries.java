package com.ledgercore.ledger;

import com.ledgercore.common.Actor;
import com.ledgercore.common.DomainException;
import com.ledgercore.ledger.domain.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only accounting evidence. Participants see their journal, never another user's statement.
 */
@Service
public class JournalQueries {
  public record Entry(
      UUID id,
      UUID ledgerAccountId,
      String classification,
      String normalDirection,
      String debit,
      String credit,
      Instant createdAt) {}

  public record View(
      UUID id,
      String reference,
      String kind,
      String currency,
      String status,
      UUID originalJournalId,
      Instant postedAt,
      List<Entry> entries) {}

  private final JdbcTemplate db;

  public JournalQueries(JdbcTemplate db) {
    this.db = db;
  }

  @Transactional(readOnly = true)
  public View get(Actor actor, UUID id) {
    var journals =
        db.query(
            """
SELECT j.* FROM journal_transactions j WHERE j.id=? AND (? OR EXISTS (
  SELECT 1 FROM journal_entries e JOIN financial_accounts a ON a.ledger_account_id=e.ledger_account_id
  WHERE e.journal_id=j.id AND a.owner_id=?))
""",
            (r, n) ->
                new View(
                    r.getObject("id", UUID.class),
                    r.getString("reference"),
                    r.getString("kind"),
                    r.getString("currency"),
                    r.getString("status"),
                    r.getObject("original_journal_id", UUID.class),
                    r.getTimestamp("posted_at").toInstant(),
                    List.of()),
            id,
            actor.admin(),
            actor.id());
    if (journals.isEmpty())
      throw new DomainException(404, "JOURNAL_NOT_FOUND", "Journal does not exist");
    View journal = journals.getFirst();
    var entries =
        db.query(
            """
SELECT e.*,l.classification,l.normal_direction FROM journal_entries e
JOIN ledger_accounts l ON l.id=e.ledger_account_id WHERE e.journal_id=? ORDER BY e.created_at,e.id
""",
            (r, n) ->
                new Entry(
                    r.getObject("id", UUID.class),
                    r.getObject("ledger_account_id", UUID.class),
                    r.getString("classification"),
                    r.getString("normal_direction"),
                    Money.format(r.getLong("debit_minor")),
                    Money.format(r.getLong("credit_minor")),
                    r.getTimestamp("created_at").toInstant()),
            id);
    return new View(
        journal.id(),
        journal.reference(),
        journal.kind(),
        journal.currency(),
        journal.status(),
        journal.originalJournalId(),
        journal.postedAt(),
        List.copyOf(entries));
  }
}
