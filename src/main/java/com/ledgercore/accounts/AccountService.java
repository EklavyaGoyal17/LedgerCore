package com.ledgercore.accounts;

import com.ledgercore.common.Actor;
import com.ledgercore.common.DomainException;
import com.ledgercore.events.EventRecorder;
import com.ledgercore.ledger.domain.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
  public record View(UUID id, String currency, String status, Instant createdAt) {}

  public record Balance(UUID accountId, String currency, String amount, String source) {}

  public record StatementEntry(
      UUID entryId,
      UUID journalId,
      String reference,
      String kind,
      String debit,
      String credit,
      String runningBalance,
      Instant postedAt) {}

  public record Page<T>(List<T> items, int offset, int limit) {}

  private final JdbcTemplate db;
  private final AccountStore accounts;
  private final EventRecorder events;

  public AccountService(JdbcTemplate db, AccountStore accounts, EventRecorder events) {
    this.db = db;
    this.accounts = accounts;
    this.events = events;
  }

  @Transactional
  public View create(Actor actor, String currency) {
    try {
      Money.Currency.valueOf(currency);
    } catch (IllegalArgumentException ex) {
      throw new DomainException(400, "UNSUPPORTED_CURRENCY", "Use INR or USD");
    }
    UUID id = UUID.randomUUID(), ledger = UUID.randomUUID();
    db.update(
        "INSERT INTO ledger_accounts(id,identifier,classification,currency,normal_direction) VALUES (?,?,'LIABILITY',?,'CREDIT')",
        ledger,
        "CUSTOMER-" + id,
        currency);
    db.update(
        "INSERT INTO financial_accounts(id,owner_id,ledger_account_id,currency) VALUES (?,?,?,?)",
        id,
        actor.id(),
        ledger,
        currency);
    events.record(actor.id(), "AccountCreated", id);
    return view(accounts.get(id));
  }

  @Transactional(readOnly = true)
  public View get(Actor actor, UUID id) {
    return view(accounts.owned(actor, id));
  }

  @Transactional(readOnly = true)
  public Page<View> list(Actor actor, int offset, int limit) {
    return new Page<>(
        db
            .query(
                "SELECT * FROM financial_accounts WHERE owner_id=? ORDER BY created_at,id LIMIT ? OFFSET ?",
                AccountStore.MAPPER,
                actor.id(),
                limit,
                offset)
            .stream()
            .map(AccountService::view)
            .toList(),
        offset,
        limit);
  }

  @Transactional(readOnly = true)
  public Balance balance(Actor actor, UUID id) {
    var a = accounts.owned(actor, id);
    Long minor =
        db.queryForObject(
            "SELECT coalesce(sum(credit_minor-debit_minor),0) FROM journal_entries WHERE ledger_account_id=?",
            Long.class,
            a.ledgerAccountId());
    return new Balance(id, a.currency(), Money.format(minor), "POSTED_JOURNAL");
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page<StatementEntry> statement(Actor actor, UUID id, int offset, int limit) {
    var a = accounts.owned(actor, id);
    String sql =
        """
      SELECT * FROM (
        SELECT e.id,e.journal_id,j.reference,j.kind,e.debit_minor,e.credit_minor,j.posted_at,
          sum(e.credit_minor-e.debit_minor) OVER (ORDER BY e.created_at,e.id ROWS UNBOUNDED PRECEDING) AS running
        FROM journal_entries e JOIN journal_transactions j ON j.id=e.journal_id WHERE e.ledger_account_id=?
      ) s ORDER BY posted_at,id LIMIT ? OFFSET ?
      """;
    return new Page<>(
        db.query(
            sql,
            (r, n) ->
                new StatementEntry(
                    r.getObject(1, UUID.class),
                    r.getObject(2, UUID.class),
                    r.getString(3),
                    r.getString(4),
                    Money.format(r.getLong(5)),
                    Money.format(r.getLong(6)),
                    Money.format(r.getLong(8)),
                    r.getTimestamp(7).toInstant()),
            a.ledgerAccountId(),
            limit,
            offset),
        offset,
        limit);
  }

  private static View view(AccountStore.Account a) {
    return new View(a.id(), a.currency(), a.status(), a.createdAt());
  }
}
