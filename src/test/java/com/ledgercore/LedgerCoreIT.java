package com.ledgercore;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ledgercore.accounts.*;
import com.ledgercore.common.*;
import com.ledgercore.events.EventConsumer;
import com.ledgercore.ledger.domain.Money;
import com.ledgercore.reconciliation.ReconciliationService;
import com.ledgercore.transfers.TransferStore;
import com.ledgercore.transfers.application.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestFaults.Configuration.class)
@Testcontainers
@org.springframework.test.annotation.DirtiesContext
class LedgerCoreIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired JdbcTemplate db;
  @Autowired AccountService accounts;
  @Autowired TransferEngine engine;
  @Autowired TransferStore transfers;
  @Autowired ReconciliationService reconciliation;
  @Autowired EventConsumer consumer;
  @Autowired TestFaults faults;
  @Autowired Json json;
  @Autowired MockMvc mvc;
  @Autowired PlatformTransactionManager manager;
  @Autowired org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;
  Actor alice, bob, admin;
  UUID a, b, c;

  @BeforeEach
  void fixture() {
    faults.clear();
    alice = user(false);
    bob = user(false);
    admin = user(true);
    a = accounts.create(alice, "INR").id();
    b = accounts.create(bob, "INR").id();
    c = accounts.create(bob, "INR").id();
  }

  Actor user(boolean adminRole) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO app_users(id,email,password_hash,role) VALUES (?,?,?,?)",
        id,
        id + "@test.invalid",
        "unused-fixture-hash",
        adminRole ? "ADMIN" : "USER");
    return new Actor(id, adminRole);
  }

  void fund(UUID account, String amount) {
    assertThat(
            engine
                .deposit(admin, UUID.randomUUID().toString(), account, Money.parse(amount, "INR"))
                .status())
        .isEqualTo(201);
  }

  Idempotency.Result send(String key, UUID source, UUID destination, String amount, Actor actor) {
    return engine.transfer(actor, key, source, destination, Money.parse(amount, "INR"));
  }

  long balance(UUID account) {
    return db.queryForObject(
        "SELECT balance_minor FROM financial_accounts WHERE id=?", Long.class, account);
  }

  long journals(UUID source) {
    return db.queryForObject(
        "SELECT count(*) FROM transfers WHERE source_id=? AND kind='TRANSFER' AND status='COMPLETED'",
        Long.class,
        source);
  }

  @Test
  void transferIsBalancedAndStatementUsesLedger() {
    fund(a, "1000");
    var result = send("transfer", a, b, "500", alice);
    assertThat(result.status()).isEqualTo(201);
    assertThat(balance(a)).isEqualTo(50000);
    assertThat(balance(b)).isEqualTo(50000);
    assertThat(accounts.balance(alice, a).amount()).isEqualTo("500.00");
    var statement = accounts.statement(alice, a, 0, 50).items();
    assertThat(statement).hasSize(2);
    assertThat(statement.getLast().runningBalance()).isEqualTo("500.00");
    assertThat(reconciliation.run(admin).status()).isEqualTo("CLEAN");
  }

  @Test
  void duplicateRequestsIncludingCanonicalAmountsPostOnlyOnce() throws Exception {
    fund(a, "1000");
    List<Idempotency.Result> results =
        race(Collections.nCopies(8, () -> send("duplicate", a, b, "100.00", alice)));
    assertThat(results).allMatch(r -> r.status() == 201);
    var ids =
        results.stream()
            .map(r -> json.read(r.body(), TransferStore.Transfer.class).id())
            .distinct()
            .toList();
    assertThat(ids).hasSize(1);
    assertThat(journals(a)).isEqualTo(1);
    assertThat(balance(a)).isEqualTo(90000);
    assertThat(
            json.read(send("duplicate", a, b, "100.0", alice).body(), TransferStore.Transfer.class)
                .id())
        .isEqualTo(ids.getFirst());
    assertThatThrownBy(() -> send("duplicate", a, b, "101", alice))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("different request");
  }

  @Test
  void repeatedConcurrentWithdrawalsCannotOverspend() throws Exception {
    for (int i = 0; i < 10; i++) {
      UUID source = accounts.create(alice, "INR").id();
      fund(source, "1000");
      List<Idempotency.Result> outcomes =
          LedgerCoreIT.<Idempotency.Result>race(
              List.of(
                  () -> send("first-" + source, source, b, "800", alice),
                  () -> send("second-" + source, source, c, "800", alice)));
      assertThat(outcomes.stream().map(Idempotency.Result::status))
          .containsExactlyInAnyOrder(201, 422);
      assertThat(balance(source)).isEqualTo(20000);
      assertThat(journals(source)).isEqualTo(1);
    }
    assertThat(reconciliation.run(admin).discrepancies()).isEmpty();
  }

  @Test
  void oppositeDirectionTransfersDoNotDeadlock() throws Exception {
    fund(a, "1000");
    fund(b, "1000");
    for (int i = 0; i < 10; i++) {
      String key = "opposite-" + i;
      assertThat(
              LedgerCoreIT.<Idempotency.Result>race(
                  List.of(() -> send(key, a, b, "10", alice), () -> send(key, b, a, "10", bob))))
          .allMatch(r -> r.status() == 201);
    }
    assertThat(balance(a)).isEqualTo(100000);
    assertThat(balance(b)).isEqualTo(100000);
  }

  @Test
  void failedOutcomeIsDurableEvenIfFundsArriveLater() {
    var failed = send("insufficient", a, b, "100", alice);
    assertThat(failed.status()).isEqualTo(422);
    fund(a, "1000");
    assertThat(send("insufficient", a, b, "100", alice).status()).isEqualTo(422);
    assertThat(journals(a)).isZero();
    assertThat(balance(a)).isEqualTo(100000);
    assertThat(send("new-attempt", a, b, "100", alice).status()).isEqualTo(201);
  }

  @ParameterizedTest
  @EnumSource(
      value = Faults.Point.class,
      names = {
        "BEFORE_TRANSACTION",
        "AFTER_VALIDATION",
        "DURING_JOURNAL",
        "AFTER_OUTBOX",
        "BEFORE_COMMIT"
      })
  void precommitFailuresRollbackEveryFinancialEffect(Faults.Point point) {
    fund(a, "1000");
    long auditBefore = db.queryForObject("SELECT count(*) FROM audit_events", Long.class);
    long outboxBefore = db.queryForObject("SELECT count(*) FROM outbox_events", Long.class);
    faults.failOnce(point);
    assertThatThrownBy(() -> send("rollback", a, b, "800", alice))
        .isInstanceOf(TestFaults.SimulatedCrash.class);
    assertThat(balance(a)).isEqualTo(100000);
    assertThat(balance(b)).isZero();
    assertThat(journals(a)).isZero();
    assertThat(db.queryForObject("SELECT count(*) FROM audit_events", Long.class))
        .isEqualTo(auditBefore);
    assertThat(db.queryForObject("SELECT count(*) FROM outbox_events", Long.class))
        .isEqualTo(outboxBefore);
    assertThat(send("rollback", a, b, "800", alice).status()).isEqualTo(201);
    assertThat(journals(a)).isEqualTo(1);
  }

  @Test
  void responseLossAfterCommitReplaysOriginalOutcome() {
    fund(a, "1000");
    faults.failOnce(Faults.Point.AFTER_COMMIT);
    assertThatThrownBy(() -> send("lost-response", a, b, "800", alice))
        .isInstanceOf(TestFaults.SimulatedCrash.class);
    var replay = send("lost-response", a, b, "800", alice);
    assertThat(replay.status()).isEqualTo(201);
    assertThat(balance(a)).isEqualTo(20000);
    assertThat(journals(a)).isEqualTo(1);
  }

  @Test
  void expiryIsInformationalAndNeverReleasesAUsedKey() {
    fund(a, "1000");
    var first = send("retained", a, b, "100", alice);
    db.update(
        "UPDATE idempotency_records SET expires_at=clock_timestamp()-interval '1 day' WHERE principal_id=? AND key='retained'",
        alice.id());
    assertThat(
            json.read(send("retained", a, b, "100", alice).body(), TransferStore.Transfer.class)
                .id())
        .isEqualTo(json.read(first.body(), TransferStore.Transfer.class).id());
    assertThat(journals(a)).isEqualTo(1);
  }

  @Test
  void reversalIsCompensatingAndCannotBeRepeatedUnderAnotherKey() {
    fund(a, "1000");
    UUID original =
        json.read(send("original", a, b, "500", alice).body(), TransferStore.Transfer.class).id();
    var reversal = engine.reverse(admin, "reverse", original);
    assertThat(reversal.status()).isEqualTo(201);
    assertThat(balance(a)).isEqualTo(100000);
    assertThat(balance(b)).isZero();
    assertThat(engine.reverse(admin, "reverse", original).status()).isEqualTo(201);
    assertThatThrownBy(() -> engine.reverse(admin, "different-key", original))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("already");
    assertThat(reconciliation.run(admin).status()).isEqualTo("CLEAN");
  }

  @Test
  void reversalCannotOverdrawRecipientAndCanBeRetriedWithANewKey() {
    fund(a, "1000");
    UUID original =
        json.read(send("original", a, b, "500", alice).body(), TransferStore.Transfer.class).id();
    send("spend", b, c, "500", bob);
    assertThat(engine.reverse(admin, "reverse", original).status()).isEqualTo(422);
    fund(b, "500");
    assertThat(engine.reverse(admin, "reverse", original).status()).isEqualTo(422);
    assertThat(engine.reverse(admin, "new-reverse", original).status()).isEqualTo(201);
  }

  @Test
  void databaseRejectsEditingDeletingAndAppendingCommittedEntries() {
    fund(a, "1000");
    UUID journal =
        db.queryForObject(
            "SELECT journal_id FROM transfers WHERE destination_id=? AND kind='DEPOSIT'",
            UUID.class,
            a);
    assertThatThrownBy(
            () ->
                db.update(
                    "UPDATE journal_entries SET debit_minor=1 WHERE journal_id=? AND debit_minor>0",
                    journal))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> db.update("DELETE FROM journal_transactions WHERE id=?", journal))
        .isInstanceOf(DataAccessException.class);
    UUID ledger =
        db.queryForObject(
            "SELECT ledger_account_id FROM financial_accounts WHERE id=?", UUID.class, a);
    assertThatThrownBy(
            () ->
                db.update(
                    "INSERT INTO journal_entries(id,journal_id,ledger_account_id,debit_minor) VALUES (?,?,?,1)",
                    UUID.randomUUID(),
                    journal,
                    ledger))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> db.update("UPDATE financial_accounts SET balance_minor=1 WHERE id=?", a))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> db.update("UPDATE financial_accounts SET balance_minor=-1 WHERE id=?", a))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void databaseRejectsUnbalancedJournalAtCommit() {
    TransactionTemplate tx = new TransactionTemplate(manager);
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      UUID key = UUID.randomUUID(), journal = UUID.randomUUID();
                      db.update(
                          "INSERT INTO idempotency_records(id,principal_id,operation,key,fingerprint,state,response_status,response_body) VALUES (?,?,'test',?,'test','FINISHED',201,'{}')",
                          key,
                          alice.id(),
                          UUID.randomUUID().toString());
                      db.update(
                          "INSERT INTO journal_transactions(id,reference,kind,currency,idempotency_reference) VALUES (?,?,'TRANSFER','INR',?)",
                          journal,
                          "INVALID-" + journal,
                          key);
                      UUID ledger =
                          db.queryForObject(
                              "SELECT ledger_account_id FROM financial_accounts WHERE id=?",
                              UUID.class,
                              a);
                      db.update(
                          "INSERT INTO journal_entries(id,journal_id,ledger_account_id,debit_minor) VALUES (?,?,?,1)",
                          UUID.randomUUID(),
                          journal,
                          ledger);
                    }))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("unbalanced journal");
  }

  @Test
  void reconcilerDetectsControlledCorruptionAndDoesNotRepairIt() {
    // Only the test fixture's database-owner connection can disable triggers.
    TransactionTemplate tx = new TransactionTemplate(manager);
    tx.executeWithoutResult(
        status -> {
          db.execute("ALTER TABLE financial_accounts DISABLE TRIGGER account_projection");
          db.update("UPDATE financial_accounts SET balance_minor=9 WHERE id=?", a);
          db.execute("ALTER TABLE financial_accounts ENABLE TRIGGER account_projection");
        });
    try {
      var report = reconciliation.run(admin);
      assertThat(report.discrepancies())
          .contains(new ReconciliationService.Discrepancy("PROJECTION_MISMATCH", a));
      assertThat(reconciliation.get(admin, report.id())).isEqualTo(report);
      assertThat(balance(a)).isEqualTo(9);
    } finally {
      tx.executeWithoutResult(
          status -> {
            db.execute("ALTER TABLE financial_accounts DISABLE TRIGGER account_projection");
            db.update("UPDATE financial_accounts SET balance_minor=0 WHERE id=?", a);
            db.execute("ALTER TABLE financial_accounts ENABLE TRIGGER account_projection");
          });
    }
  }

  @Test
  void consumerDeduplicationAndItsProjectionCommitTogether() {
    String payload =
        db.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE aggregate_id=?", String.class, a);
    faults.failOnce(Faults.Point.DURING_CONSUMER);
    assertThatThrownBy(() -> consumer.consume(payload))
        .isInstanceOf(TestFaults.SimulatedCrash.class);
    consumer.consume(payload);
    consumer.consume(payload);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM event_audit_projection WHERE aggregate_id=?", Long.class, a))
        .isEqualTo(1);
  }

  @Test
  void generatedTransferSequencesPreserveLiabilitiesAndReconcile() {
    fund(a, "1000");
    fund(b, "1000");
    fund(c, "1000");
    Random random = new Random(411);
    UUID[] ids = {a, b, c};
    for (int i = 0; i < 100; i++) {
      int from = random.nextInt(3), to = (from + 1 + random.nextInt(2)) % 3;
      send(
          "generated-" + i,
          ids[from],
          ids[to],
          Integer.toString(1 + random.nextInt(1500)),
          from == 0 ? alice : bob);
      assertThat(balance(a)).isNotNegative();
      assertThat(balance(b)).isNotNegative();
      assertThat(balance(c)).isNotNegative();
      assertThat(balance(a) + balance(b) + balance(c)).isEqualTo(300000);
    }
    assertThat(reconciliation.run(admin).discrepancies()).isEmpty();
  }

  @Test
  void httpOwnershipRolesValidationAndProblemDetails() throws Exception {
    var aliceJwt = jwt().jwt(j -> j.subject(alice.id().toString()).claim("role", "USER"));
    mvc.perform(get("/api/v1/accounts/{id}/statement", b).with(aliceJwt))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("code").value("ACCOUNT_NOT_FOUND"));
    mvc.perform(get("/api/v1/accounts/{id}/balance", a)).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/admin/reconciliation-runs").with(aliceJwt))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/accounts").param("limit", "5000").with(aliceJwt))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/transfers")
                .with(aliceJwt)
                .contentType("application/json")
                .content(
                    json.write(
                        Map.of(
                            "sourceAccountId",
                            a,
                            "destinationAccountId",
                            b,
                            "amount",
                            "0.001",
                            "currency",
                            "INR"))))
        .andExpect(status().isBadRequest());
    assertThatThrownBy(() -> send("steal", b, a, "1", alice)).isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> accounts.statement(alice, b, 0, 50))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void realBcryptLoginAndJwtValidation() throws Exception {
    String email = UUID.randomUUID() + "@example.invalid";
    String request = json.write(Map.of("email", email, "password", "Demonstration-password-123!"));
    mvc.perform(post("/api/v1/auth/register").contentType("application/json").content(request))
        .andExpect(status().isCreated());
    String login =
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content(request))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = json.read(login, com.ledgercore.security.Tokens.AccessToken.class).accessToken();
    mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token + "tamper"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(json.write(Map.of("email", email, "password", "Incorrect-password-123!"))))
        .andExpect(status().isUnauthorized());
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"expired", "issuer", "audience", "subject", "disabled", "role"})
  void invalidJwtClaimsAreRejected(String invalid) throws Exception {
    java.time.Instant now = java.time.Instant.now();
    var claims =
        org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
            .issuer(invalid.equals("issuer") ? "another-issuer" : "ledgercore")
            .audience(List.of(invalid.equals("audience") ? "another-api" : "ledgercore-api"))
            .subject(invalid.equals("subject") ? "not-a-uuid" : alice.id().toString())
            .issuedAt(now.minusSeconds(600))
            .expiresAt(invalid.equals("expired") ? now.minusSeconds(300) : now.plusSeconds(300))
            .claim("role", invalid.equals("role") ? "ADMIN" : "USER")
            .build();
    if (invalid.equals("disabled"))
      db.update("UPDATE app_users SET status='DISABLED' WHERE id=?", alice.id());
    String token =
        jwtEncoder
            .encode(
                org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                    org.springframework.security.oauth2.jwt.JwsHeader.with(
                            org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                        .build(),
                    claims))
            .getTokenValue();
    mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void validationAndObjectAuthorizationDoNotConsumeAKey() {
    fund(a, "1000");
    assertThatThrownBy(() -> send("validation", a, a, "1", alice))
        .isInstanceOf(DomainException.class);
    assertThat(send("validation", a, b, "1", alice).status()).isEqualTo(201);
    UUID usd = accounts.create(bob, "USD").id();
    assertThatThrownBy(() -> send("currency", a, usd, "1", alice))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("currency");
    UUID transfer =
        json.read(send("private", a, b, "1", alice).body(), TransferStore.Transfer.class).id();
    assertThatThrownBy(() -> transfers.owned(user(false), transfer))
        .isInstanceOf(DomainException.class);
    assertThat(transfers.owned(bob, transfer).status()).isEqualTo("COMPLETED");
  }

  @Test
  void reconciliationIndependentlyDetectsWrongTransferAmount() {
    fund(a, "1000");
    UUID id =
        json.read(send("shape", a, b, "100", alice).body(), TransferStore.Transfer.class).id();
    var tx = new TransactionTemplate(manager);
    tx.executeWithoutResult(
        status -> {
          db.execute("ALTER TABLE transfers DISABLE TRIGGER immutable_transfers");
          db.update("UPDATE transfers SET amount_minor=9999 WHERE id=?", id);
          db.execute("ALTER TABLE transfers ENABLE TRIGGER immutable_transfers");
        });
    try {
      assertThat(reconciliation.run(admin).discrepancies())
          .contains(new ReconciliationService.Discrepancy("TRANSFER_POSTING_MISMATCH", id));
    } finally {
      tx.executeWithoutResult(
          status -> {
            db.execute("ALTER TABLE transfers DISABLE TRIGGER immutable_transfers");
            db.update("UPDATE transfers SET amount_minor=10000 WHERE id=?", id);
            db.execute("ALTER TABLE transfers ENABLE TRIGGER immutable_transfers");
          });
    }
  }

  @Test
  void generatedOpenApiDescribesTheReplayableTransferResponse() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                    "$.paths['/api/v1/transfers'].post.responses['201'].content['application/json'].schema['$ref']")
                .value("#/components/schemas/Transfer"))
        .andExpect(
            jsonPath("$.components.schemas.Transfer.properties.amount.type").value("string"));
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
      for (Future<T> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
      return results;
    }
  }
}
