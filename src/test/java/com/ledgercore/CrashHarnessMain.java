package com.ledgercore;

import com.ledgercore.accounts.AccountService;
import com.ledgercore.common.Actor;
import com.ledgercore.common.Faults;
import com.ledgercore.common.Json;
import com.ledgercore.ledger.domain.Money;
import com.ledgercore.reconciliation.ReconciliationService;
import com.ledgercore.transfers.TransferStore;
import com.ledgercore.transfers.application.TransferEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** Separate JVM entry point compiled ONLY into test classes; never shipped in the application. */
public final class CrashHarnessMain {
  public static void main(String[] args) throws Exception {
    for (String credential :
        new String[] {"MIGRATION_USER", "MIGRATION_PASSWORD", "FLYWAY_USER", "FLYWAY_PASSWORD"}) {
      if (System.getenv(credential) != null)
        throw new AssertionError("Runtime child unexpectedly received " + credential);
    }
    var application = new SpringApplication(LedgerCoreApplication.class, CrashConfiguration.class);
    try (var context = application.run("--server.port=0", "--spring.profiles.active=test")) {
      UUID owner = UUID.fromString(args[1]);
      UUID receiver = UUID.fromString(args[2]);
      UUID adminId = UUID.fromString(args[3]);
      var db = context.getBean(JdbcTemplate.class);
      var accounts = context.getBean(AccountService.class);
      var engine = context.getBean(TransferEngine.class);
      var admin = new Actor(adminId, true);
      if ("seed".equals(args[0]) || "permissions".equals(args[0])) {
        for (UUID id : new UUID[] {owner, receiver, adminId}) {
          db.update(
              "INSERT INTO app_users(id,email,password_hash,role) VALUES (?,?,?,?)",
              id,
              id + "@recovery.invalid",
              "test-fixture",
              id.equals(adminId) ? "ADMIN" : "USER");
        }
        UUID source = accounts.create(new Actor(owner, false), "INR").id();
        UUID destination = accounts.create(new Actor(receiver, false), "INR").id();
        engine.deposit(admin, "seed", source, Money.parse("1000", "INR"));
        if ("permissions".equals(args[0])) {
          var sent =
              engine.transfer(
                  new Actor(owner, false),
                  "permission-transfer",
                  source,
                  destination,
                  Money.parse("1", "INR"));
          UUID transfer =
              context.getBean(Json.class).read(sent.body(), TransferStore.Transfer.class).id();
          if (engine.reverse(admin, "permission-reversal", transfer).status() != 201)
            throw new AssertionError("Runtime role cannot reverse a transfer");
        }
        return;
      }
      UUID source =
          db.queryForObject(
              "SELECT id FROM financial_accounts WHERE owner_id=?", UUID.class, owner);
      UUID destination =
          db.queryForObject(
              "SELECT id FROM financial_accounts WHERE owner_id=?", UUID.class, receiver);
      var faults = context.getBean(HaltingFaults.class);
      if (!"replay".equals(args[0])) faults.armed = Faults.Point.valueOf(args[0]);
      var result =
          engine.transfer(
              new Actor(owner, false),
              "process-recovery",
              source,
              destination,
              Money.parse("800", "INR"));
      if (result.status() != 201) throw new AssertionError("Unexpected status " + result.status());
      if (!"CLEAN".equals(context.getBean(ReconciliationService.class).run(admin).status()))
        throw new AssertionError("Reconciliation failed after restart");
      Files.writeString(Path.of(args[4]), result.body());
    }
  }

  public static final class HaltingFaults extends Faults {
    volatile Point armed;

    @Override
    public void at(Point point) {
      if (point == armed) {
        System.out.println("LEDGERCORE_PROCESS_HALT " + point);
        System.out.flush();
        Runtime.getRuntime().halt(91);
      }
    }
  }

  @TestConfiguration
  static class CrashConfiguration {
    @Bean
    @Primary
    HaltingFaults haltingFaults() {
      return new HaltingFaults();
    }
  }
}
