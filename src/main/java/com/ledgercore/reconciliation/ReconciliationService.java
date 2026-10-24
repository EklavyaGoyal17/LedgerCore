package com.ledgercore.reconciliation;

import com.ledgercore.common.Actor;
import com.ledgercore.common.DomainException;
import com.ledgercore.common.Json;
import com.ledgercore.events.EventRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {
  public record Discrepancy(String category, UUID reference) {}

  public record Report(
      UUID id,
      Instant startedAt,
      Instant finishedAt,
      long recordsChecked,
      List<Discrepancy> discrepancies,
      String status) {}

  private final JdbcTemplate db;
  private final Json json;
  private final EventRecorder events;
  private final MeterRegistry metrics;

  public ReconciliationService(
      JdbcTemplate db, Json json, EventRecorder events, MeterRegistry metrics) {
    this.db = db;
    this.json = json;
    this.events = events;
    this.metrics = metrics;
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public Report run(Actor actor) {
    requireAdmin(actor);
    Instant start = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    List<Discrepancy> errors = new ArrayList<>();
    check(
        errors,
        "UNBALANCED_JOURNAL",
        "SELECT j.id FROM journal_transactions j LEFT JOIN journal_entries e ON e.journal_id=j.id GROUP BY j.id HAVING count(e.id)<2 OR coalesce(sum(e.debit_minor),0)<>coalesce(sum(e.credit_minor),0)");
    check(
        errors,
        "PROJECTION_MISMATCH",
        "SELECT a.id FROM financial_accounts a LEFT JOIN journal_entries e ON e.ledger_account_id=a.ledger_account_id GROUP BY a.id HAVING a.balance_minor<>coalesce(sum(e.credit_minor-e.debit_minor),0) OR a.balance_minor<0");
    check(
        errors,
        "CURRENCY_MISMATCH",
        "SELECT DISTINCT j.id FROM journal_transactions j JOIN journal_entries e ON e.journal_id=j.id JOIN ledger_accounts l ON l.id=e.ledger_account_id WHERE j.currency<>l.currency");
    check(
        errors,
        "ACCOUNT_MAPPING",
        "SELECT a.id FROM financial_accounts a JOIN ledger_accounts l ON l.id=a.ledger_account_id WHERE a.currency<>l.currency OR l.classification<>'LIABILITY'");
    check(
        errors,
        "ORPHAN_JOURNAL",
        "SELECT j.id FROM journal_transactions j LEFT JOIN transfers t ON t.journal_id=j.id WHERE t.id IS NULL OR t.status<>'COMPLETED'");
    check(
        errors,
        "TRANSFER_POSTING_MISMATCH",
        """
      SELECT t.id FROM transfers t
      LEFT JOIN journal_transactions j ON j.id=t.journal_id
      LEFT JOIN financial_accounts s ON s.id=t.source_id
      JOIN financial_accounts d ON d.id=t.destination_id
      WHERE (t.status='COMPLETED' AND (j.id IS NULL OR j.kind<>t.kind OR j.currency<>t.currency
        OR d.currency<>t.currency OR (s.id IS NOT NULL AND s.currency<>t.currency)
        OR (SELECT count(*) FROM journal_entries WHERE journal_id=j.id)<>2
        OR NOT EXISTS (SELECT 1 FROM journal_entries e WHERE e.journal_id=j.id AND e.debit_minor=t.amount_minor AND e.credit_minor=0
          AND e.ledger_account_id=coalesce(s.ledger_account_id,(SELECT id FROM ledger_accounts WHERE identifier='SIMULATED-SETTLEMENT-'||t.currency)))
        OR NOT EXISTS (SELECT 1 FROM journal_entries e WHERE e.journal_id=j.id AND e.credit_minor=t.amount_minor AND e.debit_minor=0 AND e.ledger_account_id=d.ledger_account_id)))
        OR (t.status='FAILED' AND t.journal_id IS NOT NULL)
      """);
    check(
        errors,
        "INVALID_REVERSAL",
        """
      SELECT r.id FROM transfers r LEFT JOIN transfers o ON o.id=r.original_transfer_id
      LEFT JOIN journal_transactions j ON j.id=r.journal_id
      WHERE r.kind='REVERSAL' AND r.status='COMPLETED' AND
        (o.id IS NULL OR o.status<>'COMPLETED' OR o.kind<>'TRANSFER' OR r.source_id<>o.destination_id
         OR r.destination_id<>o.source_id OR r.amount_minor<>o.amount_minor OR r.currency<>o.currency
         OR j.original_journal_id IS DISTINCT FROM o.journal_id)
      """);
    check(
        errors,
        "UNFINISHED_IDEMPOTENCY",
        "SELECT id FROM idempotency_records WHERE state<>'FINISHED'");
    check(
        errors,
        "MISSING_FINANCIAL_EVENT",
        "SELECT t.id FROM transfers t WHERE NOT EXISTS (SELECT 1 FROM outbox_events o WHERE o.aggregate_id=t.id)");
    check(
        errors,
        "MISSING_FINANCIAL_AUDIT",
        "SELECT t.id FROM transfers t WHERE NOT EXISTS (SELECT 1 FROM audit_events a WHERE a.resource_id=t.id)");
    long checked =
        db.queryForObject(
            "SELECT (SELECT count(*) FROM journal_transactions)+(SELECT count(*) FROM financial_accounts)+(SELECT count(*) FROM transfers)+(SELECT count(*) FROM idempotency_records)",
            Long.class);
    Report result =
        new Report(
            UUID.randomUUID(),
            start,
            Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
            checked,
            List.copyOf(errors),
            errors.isEmpty() ? "CLEAN" : "DISCREPANCIES");
    db.update(
        "INSERT INTO reconciliation_runs(id,started_at,finished_at,records_checked,discrepancies,status) VALUES (?,?,?,?,?::jsonb,?)",
        result.id(),
        java.sql.Timestamp.from(start),
        java.sql.Timestamp.from(result.finishedAt()),
        checked,
        json.write(errors),
        result.status());
    events.record(actor.id(), "ReconciliationCompleted", result.id());
    metrics.counter("ledgercore.reconciliation.discrepancies").increment(errors.size());
    return result;
  }

  @Transactional(readOnly = true)
  public Report get(Actor actor, UUID id) {
    requireAdmin(actor);
    return db
        .query(
            "SELECT * FROM reconciliation_runs WHERE id=?",
            (r, n) ->
                new Report(
                    id,
                    r.getTimestamp("started_at").toInstant(),
                    r.getTimestamp("finished_at").toInstant(),
                    r.getLong("records_checked"),
                    List.of(json.read(r.getString("discrepancies"), Discrepancy[].class)),
                    r.getString("status")),
            id)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new DomainException(404, "RUN_NOT_FOUND", "Reconciliation run does not exist"));
  }

  private void check(List<Discrepancy> errors, String category, String sql) {
    errors.addAll(db.query(sql, (r, n) -> new Discrepancy(category, r.getObject(1, UUID.class))));
  }

  private static void requireAdmin(Actor actor) {
    if (!actor.admin()) throw new DomainException(403, "FORBIDDEN", "Administrative role required");
  }
}
