package com.ledgercore;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgercore.transfers.application.Idempotency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Upgrades populated immutable V1 history rather than testing only an empty schema. */
@Testcontainers
class LegacyMigrationIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void populatedV1UpgradePreservesBothOutcomeKindsReplayAndImmutability() throws Exception {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).target("1").load().migrate();
    JdbcTemplate db = new JdbcTemplate(source);
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    UUID admin = UUID.randomUUID(), alice = UUID.randomUUID(), bob = UUID.randomUUID();
    UUID a = UUID.randomUUID(),
        b = UUID.randomUUID(),
        la = UUID.randomUUID(),
        lb = UUID.randomUUID();
    UUID deposit = UUID.randomUUID(), completed = UUID.randomUUID(), failed = UUID.randomUUID();
    UUID depositReplay = UUID.randomUUID(),
        completedReplay = UUID.randomUUID(),
        failedReplay = UUID.randomUUID();
    UUID depositJournal = UUID.randomUUID(), transferJournal = UUID.randomUUID();
    String depositBody = response(deposit, admin, null, a, "100.00", "DEPOSIT", depositJournal);
    String completedBody = response(completed, alice, a, b, "20.00", "TRANSFER", transferJournal);
    String failedBody =
        json.writeValueAsString(
            Map.of("transferId", failed, "status", 422, "code", "INSUFFICIENT_FUNDS"));
    tx.executeWithoutResult(
        status -> {
          for (UUID actor : new UUID[] {admin, alice, bob})
            db.update(
                "INSERT INTO app_users(id,email,password_hash,role) VALUES (?,?,?,?)",
                actor,
                actor + "@migration.invalid",
                "fixture",
                actor.equals(admin) ? "ADMIN" : "USER");
          db.update(
              "INSERT INTO ledger_accounts(id,identifier,classification,currency,normal_direction)"
                  + " VALUES (?,?,'LIABILITY','INR','CREDIT'),(?,?,'LIABILITY','INR','CREDIT')",
              la,
              "CUSTOMER-" + a,
              lb,
              "CUSTOMER-" + b);
          db.update(
              "INSERT INTO financial_accounts(id,owner_id,ledger_account_id,currency,balance_minor)"
                  + " VALUES (?,?,?,'INR',8000),(?,?,?,'INR',2000)",
              a,
              alice,
              la,
              b,
              bob,
              lb);
          replay(
              db,
              depositReplay,
              admin,
              "simulated-deposit",
              "legacy-deposit",
              "legacy-deposit",
              201,
              depositBody);
          replay(
              db,
              completedReplay,
              alice,
              "transfer",
              "legacy-completed",
              "legacy-completed",
              201,
              completedBody);
          replay(
              db,
              failedReplay,
              alice,
              "transfer",
              "legacy-failed",
              "legacy-failed",
              422,
              failedBody);
          db.update(
              "INSERT INTO journal_transactions(id,reference,kind,currency,idempotency_reference)"
                  + " VALUES (?,?,'DEPOSIT','INR',?),(?,?,'TRANSFER','INR',?)",
              depositJournal,
              "LC-" + depositJournal,
              depositReplay,
              transferJournal,
              "LC-" + transferJournal,
              completedReplay);
          entry(
              db,
              depositJournal,
              UUID.fromString("00000000-0000-0000-0000-000000000001"),
              10000,
              0);
          entry(db, depositJournal, la, 0, 10000);
          entry(db, transferJournal, la, 2000, 0);
          entry(db, transferJournal, lb, 0, 2000);
          db.update(
              "INSERT INTO"
                  + " transfers(id,actor_id,source_id,destination_id,amount_minor,currency,kind,status,journal_id)"
                  + " VALUES (?,?,NULL,?,10000,'INR','DEPOSIT','COMPLETED',?)",
              deposit,
              admin,
              a,
              depositJournal);
          db.update(
              "INSERT INTO"
                  + " transfers(id,actor_id,source_id,destination_id,amount_minor,currency,kind,status,journal_id)"
                  + " VALUES (?,?,?,?,2000,'INR','TRANSFER','COMPLETED',?)",
              completed,
              alice,
              a,
              b,
              transferJournal);
          db.update(
              "INSERT INTO"
                  + " transfers(id,actor_id,source_id,destination_id,amount_minor,currency,kind,status,failure_code)"
                  + " VALUES (?,?,?,?,1000000,'INR','TRANSFER','FAILED','INSUFFICIENT_FUNDS')",
              failed,
              alice,
              a,
              b);
          evidence(db, admin, deposit, "SimulatedDepositCompleted");
          evidence(db, alice, completed, "TransferCompleted");
          evidence(db, alice, failed, "TransferFailed");
        });
    // This exact upgrade previously failed because the V1 immutable transfer trigger
    // also rejected the migration owner's update of the newly added reference column.
    Flyway upgraded = Flyway.configure().dataSource(source).load();
    assertThat(upgraded.migrate().success).isTrue();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM transfers WHERE idempotency_reference IS NOT NULL",
                Integer.class))
        .isEqualTo(3);
    assertThat(
            db.queryForObject(
                "SELECT idempotency_reference FROM transfers WHERE id=?", UUID.class, deposit))
        .isEqualTo(depositReplay);
    assertThat(
            db.queryForObject(
                "SELECT idempotency_reference FROM transfers WHERE id=?", UUID.class, completed))
        .isEqualTo(completedReplay);
    assertThat(
            db.queryForObject(
                "SELECT idempotency_reference FROM transfers WHERE id=?", UUID.class, failed))
        .isEqualTo(failedReplay);
    assertThat(
            db.queryForObject(
                "SELECT balance_minor FROM financial_accounts WHERE id=?", Long.class, a))
        .isEqualTo(8000);
    assertThat(
            db.queryForObject(
                "SELECT balance_minor FROM financial_accounts WHERE id=?", Long.class, b))
        .isEqualTo(2000);
    for (var tableAndCount :
        Map.of(
                "journal_transactions",
                2,
                "journal_entries",
                4,
                "audit_events",
                3,
                "outbox_events",
                3)
            .entrySet())
      assertThat(db.queryForObject("SELECT count(*) FROM " + tableAndCount.getKey(), Integer.class))
          .isEqualTo(tableAndCount.getValue());
    assertThat(
            db.queryForObject(
                "SELECT tgenabled::text FROM pg_trigger WHERE tgrelid='transfers'::regclass AND"
                    + " tgname='immutable_transfers'",
                String.class))
        .isEqualTo("O");
    assertThatThrownBy(() -> db.update("UPDATE transfers SET amount_minor=1 WHERE id=?", completed))
        .isInstanceOf(DataAccessException.class);
    Idempotency idempotency = new Idempotency(db);
    var replayedCompletion =
        tx.execute(
            status ->
                idempotency.execute(
                    alice,
                    "transfer",
                    "legacy-completed",
                    "legacy-completed",
                    unused -> {
                      throw new AssertionError("Legacy completion must replay");
                    }));
    var replayedFailure =
        tx.execute(
            status ->
                idempotency.execute(
                    alice,
                    "transfer",
                    "legacy-failed",
                    "legacy-failed",
                    unused -> {
                      throw new AssertionError("Legacy rejection must replay");
                    }));
    assertThat(replayedCompletion.status()).isEqualTo(201);
    assertThat(json.readTree(replayedCompletion.body())).isEqualTo(json.readTree(completedBody));
    assertThat(replayedFailure.status()).isEqualTo(422);
    assertThat(json.readTree(replayedFailure.body())).isEqualTo(json.readTree(failedBody));
    assertThat(upgraded.migrate().migrationsExecuted).isZero();
  }

  private void replay(
      JdbcTemplate db,
      UUID id,
      UUID principal,
      String operation,
      String key,
      String canonical,
      int status,
      String body) {
    db.update(
        "INSERT INTO"
            + " idempotency_records(id,principal_id,operation,key,fingerprint,state,response_status,response_body)"
            + " VALUES (?,?,?,?,?,'FINISHED',?,?::jsonb)",
        id,
        principal,
        operation,
        key,
        Idempotency.fingerprint(canonical),
        status,
        body);
  }

  private void entry(JdbcTemplate db, UUID journal, UUID ledger, long debit, long credit) {
    db.update(
        "INSERT INTO journal_entries(id,journal_id,ledger_account_id,debit_minor,credit_minor)"
            + " VALUES (?,?,?,?,?)",
        UUID.randomUUID(),
        journal,
        ledger,
        debit,
        credit);
  }

  private void evidence(JdbcTemplate db, UUID actor, UUID id, String action) {
    UUID event = UUID.randomUUID();
    db.update(
        "INSERT INTO audit_events(id,actor_id,resource_id,action,correlation_id) VALUES"
            + " (?,?,?,?,'legacy-fixture')",
        UUID.randomUUID(),
        actor,
        id,
        action);
    try {
      db.update(
          "INSERT INTO outbox_events(id,aggregate_id,event_type,payload) VALUES (?,?,?,?::jsonb)",
          event,
          id,
          action,
          json.writeValueAsString(
              Map.of(
                  "eventId",
                  event,
                  "aggregateId",
                  id,
                  "eventType",
                  action,
                  "correlationId",
                  "legacy-fixture",
                  "occurredAt",
                  "2026-10-24T00:00:00Z")));
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalStateException(error);
    }
  }

  private String response(
      UUID id, UUID actor, UUID from, UUID to, String amount, String kind, UUID journal)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", id);
    body.put("actorId", actor);
    body.put("sourceAccountId", from);
    body.put("destinationAccountId", to);
    body.put("amount", amount);
    body.put("currency", "INR");
    body.put("kind", kind);
    body.put("status", "COMPLETED");
    body.put("failureCode", null);
    body.put("journalId", journal);
    body.put("originalTransferId", null);
    body.put("createdAt", "2026-10-24T00:00:00Z");
    return json.writeValueAsString(body);
  }
}
