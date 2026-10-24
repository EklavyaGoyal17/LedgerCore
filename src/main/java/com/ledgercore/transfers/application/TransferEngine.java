package com.ledgercore.transfers.application;

import com.ledgercore.accounts.AccountStore;
import com.ledgercore.common.*;
import com.ledgercore.events.EventRecorder;
import com.ledgercore.ledger.JournalPosting;
import com.ledgercore.ledger.domain.Journal;
import com.ledgercore.ledger.domain.Money;
import com.ledgercore.transfers.TransferStore;
import com.ledgercore.transfers.domain.TransferLifecycle;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransferEngine {
  public record Command(UUID source, UUID destination, Money money, String kind, UUID original) {}

  private final JdbcTemplate db;
  private final AccountStore accounts;
  private final TransferStore transfers;
  private final Idempotency idempotency;
  private final JournalPosting posting;
  private final EventRecorder events;
  private final Json json;
  private final Faults faults;
  private final MeterRegistry metrics;
  private final ObservationRegistry observations;
  private final TransactionTemplate tx;

  public TransferEngine(
      JdbcTemplate db,
      AccountStore accounts,
      TransferStore transfers,
      Idempotency idempotency,
      JournalPosting posting,
      EventRecorder events,
      Json json,
      Faults faults,
      MeterRegistry metrics,
      ObservationRegistry observations,
      PlatformTransactionManager manager) {
    this.db = db;
    this.accounts = accounts;
    this.transfers = transfers;
    this.idempotency = idempotency;
    this.posting = posting;
    this.events = events;
    this.json = json;
    this.faults = faults;
    this.metrics = metrics;
    this.observations = observations;
    tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(20);
  }

  public Idempotency.Result transfer(
      Actor actor, String key, UUID source, UUID destination, Money money) {
    return execute(
        actor, key, "transfer", new Command(source, destination, money, "TRANSFER", null));
  }

  public Idempotency.Result deposit(Actor actor, String key, UUID destination, Money money) {
    requireAdmin(actor);
    return execute(
        actor, key, "simulated-deposit", new Command(null, destination, money, "DEPOSIT", null));
  }

  public Idempotency.Result reverse(Actor actor, String key, UUID original) {
    requireAdmin(actor);
    // Original transfer is immutable. Its authoritative data becomes the canonical request.
    var t = transfers.get(original, false);
    if (!"TRANSFER".equals(t.kind()) || !"COMPLETED".equals(t.status()))
      throw new DomainException(
          409, "NOT_REVERSIBLE", "Only completed internal transfers can be reversed");
    return execute(
        actor,
        key,
        "reversal:" + original,
        new Command(
            t.destinationAccountId(),
            t.sourceAccountId(),
            Money.parse(t.amount(), t.currency()),
            "REVERSAL",
            original));
  }

  private Idempotency.Result execute(Actor actor, String key, String operation, Command c) {
    faults.at(Faults.Point.BEFORE_TRANSACTION);
    String canonical =
        c.kind()
            + "|"
            + c.source()
            + "|"
            + c.destination()
            + "|"
            + c.money().minor()
            + "|"
            + c.money().currency()
            + "|"
            + c.original();
    var sample = io.micrometer.core.instrument.Timer.start(metrics);
    try {
      Idempotency.Result result =
          Observation.createNotStarted("ledgercore.transfer", observations)
              .observe(
                  () -> {
                    for (int attempt = 0; ; attempt++) {
                      try {
                        return tx.execute(
                            status -> {
                              db.execute("SET LOCAL lock_timeout = '5s'");
                              Idempotency.Result response =
                                  idempotency.execute(
                                      actor.id(),
                                      operation,
                                      key,
                                      canonical,
                                      id -> post(actor, c, id));
                              faults.at(Faults.Point.BEFORE_COMMIT);
                              return response;
                            });
                      } catch (PessimisticLockingFailureException ex) {
                        if (attempt >= 2)
                          throw new DomainException(
                              503,
                              "CONCURRENCY_RETRY",
                              "Lock contention; retry with the original idempotency key");
                        metrics.counter("ledgercore.transfer.retries").increment();
                      }
                    }
                  });
      faults.at(Faults.Point.AFTER_COMMIT);
      return result;
    } finally {
      sample.stop(metrics.timer("ledgercore.transfer.duration"));
    }
  }

  private Idempotency.Result post(Actor actor, Command c, UUID idempotencyId) {
    var lifecycle = new TransferLifecycle();
    lifecycle.transition(TransferLifecycle.State.PROCESSING);
    if (c.source() != null && c.source().equals(c.destination()))
      throw new DomainException(400, "SAME_ACCOUNT", "Source and destination must differ");
    if (c.original() != null) {
      transfers.get(c.original(), true);
      if (db.queryForObject(
              "SELECT count(*) FROM transfers WHERE original_transfer_id=? AND status='COMPLETED'",
              Integer.class,
              c.original())
          > 0)
        throw new DomainException(409, "ALREADY_REVERSED", "Transfer has already been reversed");
    }
    List<AccountStore.Account> locked =
        accounts.lock(
            c.source() == null ? List.of(c.destination()) : List.of(c.source(), c.destination()));
    var destination =
        locked.stream().filter(a -> a.id().equals(c.destination())).findFirst().orElseThrow();
    var source =
        c.source() == null
            ? null
            : locked.stream().filter(a -> a.id().equals(c.source())).findFirst().orElseThrow();
    if (source != null && c.kind().equals("TRANSFER") && !source.ownerId().equals(actor.id()))
      throw new DomainException(404, "ACCOUNT_NOT_FOUND", "Account does not exist");
    for (var a : locked) {
      if (!a.currency().equals(c.money().currency().name()))
        throw new DomainException(
            422, "CURRENCY_MISMATCH", "Both accounts must use the request currency");
      if (!a.status().equals("ACTIVE"))
        throw new DomainException(
            422, "ACCOUNT_INACTIVE", "Account cannot participate in transfers");
    }
    faults.at(Faults.Point.AFTER_VALIDATION);
    UUID transferId = UUID.randomUUID(), journalId = null;
    String failure =
        source != null && source.balanceMinor() < c.money().minor() ? "INSUFFICIENT_FUNDS" : null;
    if (failure == null) {
      UUID debitLedger =
          source == null
              ? db.queryForObject(
                  "SELECT id FROM ledger_accounts WHERE identifier=?",
                  UUID.class,
                  "SIMULATED-SETTLEMENT-" + c.money().currency())
              : source.ledgerAccountId();
      UUID originalJournal =
          c.original() == null ? null : transfers.get(c.original(), false).journalId();
      journalId =
          posting.post(
              c.kind(),
              idempotencyId,
              originalJournal,
              new Journal(
                  List.of(
                      new Journal.Entry(debitLedger, c.money().currency(), c.money().minor(), 0),
                      new Journal.Entry(
                          destination.ledgerAccountId(),
                          c.money().currency(),
                          0,
                          c.money().minor()))));
      if (source != null)
        db.update(
            "UPDATE financial_accounts SET balance_minor=balance_minor-? WHERE id=?",
            c.money().minor(),
            source.id());
      db.update(
          "UPDATE financial_accounts SET balance_minor=balance_minor+? WHERE id=?",
          c.money().minor(),
          destination.id());
      lifecycle.transition(TransferLifecycle.State.COMPLETED);
    } else {
      lifecycle.transition(TransferLifecycle.State.FAILED);
    }
    db.update(
        "INSERT INTO transfers(id,actor_id,source_id,destination_id,amount_minor,currency,kind,status,failure_code,journal_id,original_transfer_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        transferId,
        actor.id(),
        c.source(),
        c.destination(),
        c.money().minor(),
        c.money().currency().name(),
        c.kind(),
        lifecycle.state().name(),
        failure,
        journalId,
        c.original());
    String event =
        failure != null
            ? "TransferFailed"
            : switch (c.kind()) {
              case "REVERSAL" -> "ReversalCompleted";
              case "DEPOSIT" -> "SimulatedDepositCompleted";
              default -> "TransferCompleted";
            };
    events.record(actor.id(), event, transferId);
    faults.at(Faults.Point.AFTER_OUTBOX);
    org.springframework.transaction.support.TransactionSynchronizationManager
        .registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
              @Override
              public void afterCommit() {
                metrics
                    .counter(
                        "ledgercore.transfer.committed",
                        "outcome",
                        failure == null ? "completed" : "failed",
                        "category",
                        failure == null ? "none" : failure)
                    .increment();
              }
            });
    if (failure != null)
      return new Idempotency.Result(
          422,
          json.write(
              Map.of(
                  "type",
                  "urn:ledgercore:problem:insufficient_funds",
                  "title",
                  failure,
                  "status",
                  422,
                  "code",
                  failure,
                  "detail",
                  "Available funds are insufficient",
                  "transferId",
                  transferId)));
    return new Idempotency.Result(201, json.write(transfers.get(transferId, false)));
  }

  private static void requireAdmin(Actor actor) {
    if (!actor.admin()) throw new DomainException(403, "FORBIDDEN", "Administrative role required");
  }
}
