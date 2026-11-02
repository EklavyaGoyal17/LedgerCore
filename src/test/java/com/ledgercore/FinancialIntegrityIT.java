package com.ledgercore;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.ledgercore.accounts.AccountService;
import com.ledgercore.common.Actor;
import com.ledgercore.common.DomainException;
import com.ledgercore.common.Json;
import com.ledgercore.ledger.domain.Money;
import com.ledgercore.reconciliation.ReconciliationService;
import com.ledgercore.transfers.TransferStore;
import com.ledgercore.transfers.application.Idempotency;
import com.ledgercore.transfers.application.TransferEngine;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL constraints, contention, corruption detection, and replay durability. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext
class FinancialIntegrityIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired JdbcTemplate db;
  @Autowired DataSource dataSource;
  @Autowired AccountService accounts;
  @Autowired TransferEngine engine;
  @Autowired ReconciliationService reconciliation;
  @Autowired PlatformTransactionManager manager;
  @Autowired Json json;
  @Autowired MeterRegistry metrics;
  Actor alice, bob, admin;
  UUID source, destination;

  @BeforeEach
  void fixture() {
    alice = user(false);
    bob = user(false);
    admin = user(true);
    source = accounts.create(alice, "INR").id();
    destination = accounts.create(bob, "INR").id();
  }

  private Actor user(boolean administrative) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO app_users(id,email,password_hash,role) VALUES (?,?,?,?)",
        id,
        id + "@integrity.invalid",
        "fixture-only",
        administrative ? "ADMIN" : "USER");
    return new Actor(id, administrative);
  }

  private UUID fund(UUID account, String amount) {
    return transferId(
        engine.deposit(admin, UUID.randomUUID().toString(), account, Money.parse(amount, "INR")));
  }

  private Idempotency.Result send(String key, String amount) {
    return engine.transfer(alice, key, source, destination, Money.parse(amount, "INR"));
  }

  private UUID transferId(Idempotency.Result result) {
    assertThat(result.status()).isEqualTo(201);
    return json.read(result.body(), TransferStore.Transfer.class).id();
  }

  private long balance(UUID id) {
    return db.queryForObject(
        "SELECT balance_minor FROM financial_accounts WHERE id=?", Long.class, id);
  }

  @Test
  void committedReplayIdentityOutcomeAndFinancialEventEvidenceAreImmutable() {
    fund(source, "100");
    UUID id = transferId(send("immutable-replay", "10"));
    UUID record =
        db.queryForObject("SELECT idempotency_reference FROM transfers WHERE id=?", UUID.class, id);
    for (String assignment :
        List.of(
            "fingerprint='changed'",
            "key='changed'",
            "response_status=422",
            "response_body='{}'::jsonb",
            "state='PROCESSING',response_status=NULL,response_body=NULL")) {
      assertThatThrownBy(
              () ->
                  db.update("UPDATE idempotency_records SET " + assignment + " WHERE id=?", record))
          .isInstanceOf(DataAccessException.class);
    }
    assertThatThrownBy(() -> db.update("DELETE FROM idempotency_records WHERE id=?", record))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> db.update("UPDATE outbox_events SET payload='{}' WHERE aggregate_id=?", id))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> db.update("DELETE FROM outbox_events WHERE aggregate_id=?", id))
        .isInstanceOf(DataAccessException.class);
    // Retention metadata and publisher bookkeeping can change without releasing a used key.
    db.update(
        "UPDATE idempotency_records SET expires_at=clock_timestamp()-interval '1 day' WHERE id=?",
        record);
    db.update("UPDATE outbox_events SET attempts=attempts+1 WHERE aggregate_id=?", id);
    assertThat(transferId(send("immutable-replay", "10"))).isEqualTo(id);
    assertThat(reconciliation.run(admin).status()).isEqualTo("CLEAN");
  }

  @Test
  void failedOutcomeCannotCommitWithoutMatchingAuditAndOutbox() {
    assertThatThrownBy(() -> manualFailedOutcome(false, false, false))
        .hasStackTraceContaining("matching audit and outbox evidence");
    assertThatThrownBy(() -> manualFailedOutcome(true, false, false))
        .hasStackTraceContaining("matching audit and outbox evidence");
    assertThatThrownBy(() -> manualFailedOutcome(true, true, true))
        .hasStackTraceContaining("replay outcome disagree");
    assertThat(reconciliation.run(admin).status()).isEqualTo("CLEAN");
  }

  private void manualFailedOutcome(boolean audit, boolean event, boolean wrongReplay) {
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              UUID id = UUID.randomUUID(), replay = UUID.randomUUID(), eventId = UUID.randomUUID();
              db.update(
                  "INSERT INTO"
                      + " idempotency_records(id,principal_id,operation,key,fingerprint,state,response_status,response_body)"
                      + " VALUES (?,?,'transfer',?,'fixture','FINISHED',422,?::jsonb)",
                  replay,
                  alice.id(),
                  id.toString(),
                  json.write(
                      Map.of(
                          "transferId",
                          id,
                          "code",
                          wrongReplay ? "WRONG_FAILURE" : "INSUFFICIENT_FUNDS")));
              db.update(
                  "INSERT INTO"
                      + " transfers(id,actor_id,source_id,destination_id,amount_minor,currency,kind,status,failure_code,idempotency_reference)"
                      + " VALUES (?,?,?,?,1,'INR','TRANSFER','FAILED','INSUFFICIENT_FUNDS',?)",
                  id,
                  alice.id(),
                  source,
                  destination,
                  replay);
              if (audit)
                db.update(
                    "INSERT INTO audit_events(id,actor_id,action,resource_id,correlation_id) VALUES"
                        + " (?,?,'TransferFailed',?,'fixture')",
                    UUID.randomUUID(),
                    alice.id(),
                    id);
              if (event)
                db.update(
                    "INSERT INTO outbox_events(id,aggregate_id,event_type,payload) VALUES"
                        + " (?,?,'TransferFailed',?::jsonb)",
                    eventId,
                    id,
                    json.write(
                        Map.of(
                            "eventId", eventId,
                            "aggregateId", id,
                            "eventType", "TransferFailed",
                            "correlationId", "fixture",
                            "occurredAt", "2026-10-24T00:00:00Z")));
            });
  }

  @Test
  void eventEnvelopeCannotMisrepresentItsDurableIdentity() {
    assertThatThrownBy(
            () ->
                db.update(
                    "INSERT INTO outbox_events(id,aggregate_id,event_type,payload) VALUES"
                        + " (?,?,'TransferCompleted','{}')",
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("outbox envelope");
  }

  @Test
  void correctlyShapedFinancialEventsStillRequireARealBusinessOutcome() {
    UUID event = UUID.randomUUID(), aggregate = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                db.update(
                    "INSERT INTO outbox_events(id,aggregate_id,event_type,payload) VALUES"
                        + " (?,?,'TransferCompleted',?::jsonb)",
                    event,
                    aggregate,
                    json.write(
                        Map.of(
                            "eventId", event,
                            "aggregateId", aggregate,
                            "eventType", "TransferCompleted",
                            "correlationId", "fixture",
                            "occurredAt", "2026-10-24T00:00:00Z"))))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("does not reference its business outcome");
    assertThatThrownBy(
            () ->
                db.update(
                    "INSERT INTO audit_events(id,actor_id,action,resource_id,correlation_id) VALUES"
                        + " (?,?,'TransferCompleted',?,'fixture')",
                    UUID.randomUUID(),
                    alice.id(),
                    aggregate))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("does not reference its business outcome");
  }

  @Test
  void directSqlCannotBypassCurrencyStatusOrSourceOwnershipEvenForRejectedOutcomes() {
    UUID originalDestination = destination;
    destination = accounts.create(bob, "USD").id();
    try {
      assertThatThrownBy(() -> manualFailedOutcome(true, true, false))
          .hasStackTraceContaining("account currency or status mismatch");
    } finally {
      destination = originalDestination;
    }
    db.update("UPDATE financial_accounts SET status='FROZEN' WHERE id=?", source);
    try {
      assertThatThrownBy(() -> manualFailedOutcome(true, true, false))
          .hasStackTraceContaining("account currency or status mismatch");
    } finally {
      db.update("UPDATE financial_accounts SET status='ACTIVE' WHERE id=?", source);
    }
    Actor originalActor = alice;
    alice = bob;
    try {
      assertThatThrownBy(() -> manualFailedOutcome(true, true, false))
          .hasStackTraceContaining("does not own the source");
    } finally {
      alice = originalActor;
    }
    assertThat(reconciliation.run(admin).status()).isEqualTo("CLEAN");
  }

  @Test
  void directJournalEntryCannotMixInrAndUsd() {
    UUID usd = accounts.create(bob, "USD").id();
    assertThatThrownBy(
            () ->
                new TransactionTemplate(manager)
                    .executeWithoutResult(
                        status -> {
                          UUID replay = UUID.randomUUID(), journal = UUID.randomUUID();
                          db.update(
                              "INSERT INTO"
                                  + " idempotency_records(id,principal_id,operation,key,fingerprint,state,response_status,response_body)"
                                  + " VALUES"
                                  + " (?,?,'constraint-test',?,'fixture','FINISHED',201,'{}')",
                              replay,
                              alice.id(),
                              replay.toString());
                          db.update(
                              "INSERT INTO"
                                  + " journal_transactions(id,reference,kind,currency,idempotency_reference)"
                                  + " VALUES (?,?,'TRANSFER','INR',?)",
                              journal,
                              "INVALID-" + journal,
                              replay);
                          db.update(
                              "INSERT INTO"
                                  + " journal_entries(id,journal_id,ledger_account_id,credit_minor)"
                                  + " SELECT ?,?,ledger_account_id,1 FROM financial_accounts WHERE"
                                  + " id=?",
                              UUID.randomUUID(),
                              journal,
                              usd);
                        }))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("journal currency mismatch");
    assertThat(reconciliation.run(admin).status()).isEqualTo("CLEAN");
  }

  @Test
  void concurrentDifferentPayloadsShareOnlyOneDurableOutcome() throws Exception {
    fund(source, "100");
    List<Object> outcomes =
        race(
            List.of(
                () -> outcome(() -> send("conflicting-race", "10")),
                () -> outcome(() -> send("conflicting-race", "20"))));
    assertThat(outcomes.stream().filter(Idempotency.Result.class::isInstance)).hasSize(1);
    assertThat(outcomes.stream().filter(DomainException.class::isInstance))
        .singleElement()
        .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    assertThat(balance(source)).isIn(9000L, 8000L);
    assertThat(reconciliation.run(admin).status()).isEqualTo("CLEAN");
  }

  @Test
  void concurrentReversalKeysCannotCompensateTwice() throws Exception {
    fund(source, "100");
    UUID original = transferId(send("reversible", "40"));
    List<Supplier<Object>> tasks = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      String key = "distinct-reverse-" + i;
      tasks.add(() -> outcome(() -> engine.reverse(admin, key, original)));
    }
    List<Object> outcomes = race(tasks);
    assertThat(outcomes.stream().filter(Idempotency.Result.class::isInstance)).hasSize(1);
    assertThat(outcomes.stream().filter(DomainException.class::isInstance))
        .hasSize(5)
        .allSatisfy(e -> assertThat(((DomainException) e).code()).isEqualTo("ALREADY_REVERSED"));
    assertThat(balance(source)).isEqualTo(10000);
    assertThat(balance(destination)).isZero();
    assertThat(reconciliation.run(admin).status()).isEqualTo("CLEAN");
  }

  @Test
  void concurrentIdenticalReversalsReplayTheSingleCompensation() throws Exception {
    fund(source, "100");
    UUID original = transferId(send("reverse-same", "40"));
    List<Supplier<Idempotency.Result>> tasks = new ArrayList<>();
    for (int i = 0; i < 6; i++) tasks.add(() -> engine.reverse(admin, "same-reverse", original));
    assertThat(race(tasks).stream().map(this::transferId).distinct()).hasSize(1);
    assertThat(balance(source)).isEqualTo(10000);
    assertThat(balance(destination)).isZero();
  }

  @Test
  void postgresLockTimeoutRollsBackAndRetriesTheEntireIdempotentOperation() throws Exception {
    fund(source, "100");
    double before = retryCount();
    try (Connection blocker = dataSource.getConnection();
        ExecutorService pool = Executors.newSingleThreadExecutor()) {
      blocker.setAutoCommit(false);
      lock(blocker, source);
      Future<Idempotency.Result> pending = pool.submit(() -> send("lock-timeout", "10"));
      try {
        await().atMost(Duration.ofSeconds(9)).until(() -> retryCount() > before);
      } finally {
        blocker.commit();
      }
      assertThat(pending.get(15, TimeUnit.SECONDS).status()).isEqualTo(201);
    }
    assertThat(balance(source)).isEqualTo(9000);
    assertThat(reconciliation.run(admin).status()).isEqualTo("CLEAN");
  }

  @Test
  void realPostgresDeadlockVictimRetriesWithoutDuplicatePosting() throws Exception {
    // The engine orders locks consistently. An external transaction deliberately violates that
    // order. The engine's shorter deadlock detector interval chooses it as the victim.
    UUID other = accounts.create(alice, "INR").id();
    List<UUID> ordered = List.of(source, other).stream().sorted().toList();
    UUID first = ordered.getFirst(), second = ordered.getLast();
    fund(first, "100");
    double before = retryCount();
    try (Connection external = dataSource.getConnection();
        ExecutorService pool = Executors.newSingleThreadExecutor()) {
      external.setAutoCommit(false);
      try (var statement = external.createStatement()) {
        statement.execute("SET LOCAL deadlock_timeout='10s'");
        statement.execute("SET LOCAL lock_timeout='12s'");
      }
      lock(external, second);
      Future<Idempotency.Result> pending =
          pool.submit(
              () ->
                  engine.transfer(alice, "real-deadlock", first, second, Money.parse("10", "INR")));
      try {
        await()
            .atMost(Duration.ofSeconds(3))
            .until(
                () ->
                    db.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()"
                                + " AND wait_event_type='Lock' AND query LIKE 'SELECT * FROM"
                                + " financial_accounts WHERE id=%'",
                            Integer.class)
                        > 0);
        lock(external, first);
      } finally {
        external.commit();
      }
      assertThat(pending.get(15, TimeUnit.SECONDS).status()).isEqualTo(201);
    }
    assertThat(retryCount()).isGreaterThan(before);
    assertThat(balance(first)).isEqualTo(9000);
    assertThat(balance(second)).isEqualTo(1000);
    assertThat(reconciliation.run(admin).status()).isEqualTo("CLEAN");
  }

  private static void lock(Connection connection, UUID id) throws Exception {
    try (var statement =
        connection.prepareStatement("SELECT id FROM financial_accounts WHERE id=? FOR UPDATE")) {
      statement.setObject(1, id);
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
      }
    }
  }

  private double retryCount() {
    var counter = metrics.find("ledgercore.transfer.retries").counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void destinationOverflowIsADurableBusinessRejectionWithoutAnyPosting() {
    fund(source, "100");
    // Privileged test-only corruption fixture exercises the otherwise impractical bigint limit.
    corrupt(
        () ->
            db.update(
                "UPDATE financial_accounts SET balance_minor=? WHERE id=?",
                Long.MAX_VALUE,
                destination));
    try {
      Idempotency.Result failed = send("overflow", "1");
      assertThat(failed.status()).isEqualTo(422);
      assertThat(failed.body()).contains("BALANCE_LIMIT_EXCEEDED");
      assertThat(balance(source)).isEqualTo(10000);
      assertThat(balance(destination)).isEqualTo(Long.MAX_VALUE);
      assertThat(send("overflow", "1").status()).isEqualTo(422);
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM transfers WHERE source_id=? AND journal_id IS NOT NULL",
                  Long.class,
                  source))
          .isZero();
    } finally {
      corrupt(
          () -> db.update("UPDATE financial_accounts SET balance_minor=0 WHERE id=?", destination));
    }
    assertThat(reconciliation.run(admin).status()).isEqualTo("CLEAN");
  }

  @Test
  void statementsUseTheSameTotalOrderForPaginationAndRunningBalances() {
    UUID deposit = fund(source, "100");
    UUID transfer = transferId(send("statement-order", "25"));
    // Equal posting timestamps and deliberately opposite insertion timestamps exercise the tie.
    corrupt(
        () -> {
          db.update(
              "UPDATE journal_transactions SET posted_at='2026-01-01T00:00:00Z' WHERE id IN (SELECT"
                  + " journal_id FROM transfers WHERE id IN (?,?))",
              deposit,
              transfer);
          db.update(
              "UPDATE journal_entries SET created_at=CASE WHEN credit_minor>0 THEN"
                  + " '2026-01-02T00:00:00Z'::timestamptz ELSE '2026-01-01T00:00:00Z'::timestamptz"
                  + " END WHERE ledger_account_id=(SELECT ledger_account_id FROM financial_accounts"
                  + " WHERE id=?)",
              source);
        });
    var full = accounts.statement(alice, source, 0, 10).items();
    assertThat(full).hasSize(2);
    long running = 0;
    for (var entry : full) {
      running += new java.math.BigDecimal(entry.credit()).movePointRight(2).longValueExact();
      running -= new java.math.BigDecimal(entry.debit()).movePointRight(2).longValueExact();
      assertThat(entry.runningBalance()).isEqualTo(Money.format(running));
    }
    assertThat(accounts.statement(alice, source, 0, 1).items()).containsExactly(full.getFirst());
    assertThat(accounts.statement(alice, source, 1, 1).items()).containsExactly(full.getLast());
    assertThat(full.getLast().runningBalance()).isEqualTo("75.00");
  }

  @Test
  void reconciliationFlagsEventAuditReplayAndReversalCorruptionWithoutRepair() {
    fund(source, "100");
    UUID original = transferId(send("corrupt-reversal", "20"));
    UUID reversed = transferId(engine.reverse(admin, "corrupt-reversal-undo", original));
    UUID replay =
        db.queryForObject(
            "SELECT idempotency_reference FROM transfers WHERE id=?", UUID.class, reversed);
    String originalPayload =
        db.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE aggregate_id=?", String.class, reversed);
    corrupt(
        () -> {
          db.update("UPDATE transfers SET amount_minor=1999 WHERE id=?", reversed);
          db.update("UPDATE idempotency_records SET response_body='{}' WHERE id=?", replay);
          db.update(
              "UPDATE outbox_events SET event_type='TransferCompleted' WHERE aggregate_id=?",
              reversed);
          db.update(
              "UPDATE audit_events SET action='TransferCompleted' WHERE resource_id=?", reversed);
        });
    try {
      var report = reconciliation.run(admin);
      assertThat(report.discrepancies())
          .contains(
              new ReconciliationService.Discrepancy("TRANSFER_POSTING_MISMATCH", reversed),
              new ReconciliationService.Discrepancy("INVALID_REVERSAL", reversed),
              new ReconciliationService.Discrepancy("IDEMPOTENCY_OUTCOME_MISMATCH", reversed),
              new ReconciliationService.Discrepancy("MISSING_FINANCIAL_EVENT", reversed),
              new ReconciliationService.Discrepancy("MISSING_FINANCIAL_AUDIT", reversed));
      assertThat(reconciliation.get(admin, report.id())).isEqualTo(report);
      assertThat(
              db.queryForObject(
                  "SELECT response_body::text FROM idempotency_records WHERE id=?",
                  String.class,
                  replay))
          .isEqualTo("{}");
    } finally {
      corrupt(
          () -> {
            db.update("UPDATE transfers SET amount_minor=2000 WHERE id=?", reversed);
            // Reconstruct the original immutable response from the authoritative transfer view.
            var transfer = new TransferStore(db).get(reversed, false);
            db.update(
                "UPDATE idempotency_records SET response_body=?::jsonb WHERE id=?",
                json.write(transfer),
                replay);
            db.update(
                "UPDATE outbox_events SET event_type='ReversalCompleted',payload=?::jsonb WHERE"
                    + " aggregate_id=?",
                originalPayload,
                reversed);
            db.update(
                "UPDATE audit_events SET action='ReversalCompleted' WHERE resource_id=?", reversed);
          });
    }
    assertThat(reconciliation.run(admin).status()).isEqualTo("CLEAN");
  }

  @ParameterizedTest
  @ValueSource(longs = {11, 59, 411, 20261024})
  void generatedDatabaseSequencesPreserveBothCurrenciesReplaysAndCompensations(long seed) {
    Random random = new Random(seed);
    for (String currency : List.of("INR", "USD")) {
      UUID[] ids = {
        accounts.create(alice, currency).id(),
        accounts.create(alice, currency).id(),
        accounts.create(alice, currency).id()
      };
      for (UUID account : ids)
        engine.deposit(admin, UUID.randomUUID().toString(), account, Money.parse("1000", currency));
      for (int step = 0; step < 40; step++) {
        int from = random.nextInt(3), to = (from + 1 + random.nextInt(2)) % 3;
        Money money = new Money(1 + random.nextInt(140000), Money.Currency.valueOf(currency));
        String key = "generated-" + seed + "-" + currency + "-" + step;
        var result = engine.transfer(alice, key, ids[from], ids[to], money);
        assertThat(
                json.read(engine.transfer(alice, key, ids[from], ids[to], money).body(), Map.class))
            .isEqualTo(json.read(result.body(), Map.class));
        if (result.status() == 201 && random.nextInt(4) == 0)
          assertThat(engine.reverse(admin, "undo-" + key, transferId(result)).status())
              .isEqualTo(201);
        assertThat(balance(ids[0]) + balance(ids[1]) + balance(ids[2])).isEqualTo(300000);
        for (UUID account : ids) assertThat(balance(account)).isNotNegative();
      }
    }
    assertThat(reconciliation.run(admin).status()).isEqualTo("CLEAN");
  }

  private void corrupt(Runnable mutation) {
    // This capability exists solely on the ephemeral test container's owner connection.
    new TransactionTemplate(manager)
        .executeWithoutResult(
            status -> {
              db.execute("SET LOCAL session_replication_role='replica'");
              mutation.run();
            });
  }

  private static Object outcome(Supplier<Idempotency.Result> action) {
    try {
      return action.get();
    } catch (DomainException e) {
      return e;
    }
  }

  private static <T> List<T> race(List<Supplier<T>> tasks) throws Exception {
    try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
      CountDownLatch ready = new CountDownLatch(tasks.size()), start = new CountDownLatch(1);
      List<Future<T>> futures = new ArrayList<>();
      for (var task : tasks)
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("start timeout");
                  return task.get();
                }));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<T> results = new ArrayList<>();
      for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
      return results;
    }
  }
}
