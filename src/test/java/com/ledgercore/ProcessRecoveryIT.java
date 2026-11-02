package com.ledgercore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class ProcessRecoveryIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");

  @TempDir Path directory;

  @BeforeAll
  static void provisionRoles() throws Exception {
    POSTGRES.copyFileToContainer(
        MountableFile.forHostPath(
            Path.of("infrastructure/postgres/bootstrap.sql").toAbsolutePath()),
        "/tmp/bootstrap.sql");
    var result =
        POSTGRES.execInContainer(
            "psql",
            "-U",
            POSTGRES.getUsername(),
            "-d",
            POSTGRES.getDatabaseName(),
            "-v",
            "ON_ERROR_STOP=1",
            "-v",
            "migration_password=test-migration",
            "-v",
            "runtime_password=test-runtime",
            "-f",
            "/tmp/bootstrap.sql");
    assertThat(result.getExitCode()).withFailMessage("%s", result.getStderr()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"BEFORE_COMMIT", "AFTER_COMMIT"})
  void abruptJvmHaltAndNewProcessReplayPreserveExactlyOnePosting(String point) throws Exception {
    List<String> fixture =
        List.of(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString());
    run("migrate", fixture, 0, "migration");
    run("seed", fixture, 0, "seed");
    run(point, fixture, 91, "crash");
    try (var connection = ownerConnection();
        var statement =
            connection.prepareStatement("SELECT count(*) FROM transfers WHERE actor_id=?")) {
      statement.setObject(1, UUID.fromString(fixture.getFirst()));
      try (var result = statement.executeQuery()) {
        result.next();
        assertThat(result.getLong(1)).isEqualTo("BEFORE_COMMIT".equals(point) ? 0 : 1);
      }
    }
    run("replay", fixture, 0, "replay-one");
    run("replay", fixture, 0, "replay-two");
    var json = new ObjectMapper();
    assertThat(json.readTree(Files.readString(directory.resolve("replay-one.json"))))
        .isEqualTo(json.readTree(Files.readString(directory.resolve("replay-two.json"))));
    try (var connection = ownerConnection();
        var statement =
            connection.prepareStatement(
                "SELECT a.balance_minor, (SELECT count(*) FROM transfers WHERE actor_id=a.owner_id)"
                    + " AS n, (SELECT count(*) FROM journal_entries e JOIN transfers t ON"
                    + " t.journal_id=e.journal_id WHERE t.actor_id=a.owner_id) AS entries, (SELECT"
                    + " count(*) FROM outbox_events o JOIN transfers t ON o.aggregate_id=t.id WHERE"
                    + " t.actor_id=a.owner_id) AS events, (SELECT count(*) FROM audit_events v JOIN"
                    + " transfers t ON v.resource_id=t.id WHERE t.actor_id=a.owner_id) AS audit"
                    + " FROM financial_accounts a WHERE a.owner_id=?")) {
      statement.setObject(1, UUID.fromString(fixture.getFirst()));
      try (var result = statement.executeQuery()) {
        result.next();
        assertThat(result.getLong("balance_minor")).isEqualTo(20000);
        assertThat(result.getLong("n")).isEqualTo(1);
        assertThat(result.getLong("entries")).isEqualTo(2);
        assertThat(result.getLong("events")).isEqualTo(1);
        assertThat(result.getLong("audit")).isEqualTo(1);
      }
    }
  }

  @Test
  void runtimeCannotOwnSchemaTruncateOrModifyImmutableHistory() throws Exception {
    List<String> fixture =
        List.of(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString());
    run("migrate", fixture, 0, "migration");
    run("permissions", fixture, 0, "privileges-seed");
    try (var connection =
        DriverManager.getConnection(POSTGRES.getJdbcUrl(), "ledgercore_runtime", "test-runtime")) {
      for (String sql :
          List.of(
              "CREATE TABLE public.forbidden(id integer)",
              "CREATE TEMP TABLE forbidden_temp(id integer)",
              "TRUNCATE journal_transactions CASCADE",
              "ALTER TABLE journal_entries DISABLE TRIGGER ALL",
              "UPDATE journal_entries SET debit_minor=0",
              "DELETE FROM journal_transactions",
              "SELECT * FROM flyway_schema_history")) {
        assertThatThrownBy(() -> connection.createStatement().execute(sql))
            .as(sql)
            .isInstanceOf(SQLException.class)
            .satisfies(ex -> assertThat(((SQLException) ex).getSQLState()).isEqualTo("42501"));
      }
    }
  }

  private Connection ownerConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private void run(String mode, List<String> fixture, int expectedExit, String output)
      throws Exception {
    Path log = directory.resolve(output + ".log");
    Path arguments = directory.resolve(output + ".args");
    var command = new ArrayList<String>();
    command.add("-cp");
    command.add(
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
    boolean migration = "migrate".equals(mode);
    if (migration) {
      command.add(LedgerCoreApplication.class.getName());
      command.add("--migrate-only");
    } else {
      command.add(CrashHarnessMain.class.getName());
      command.add(mode);
      command.addAll(fixture);
      command.add(directory.resolve(output + ".json").toString());
    }
    Files.writeString(
        arguments,
        command.stream()
            .map(s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .collect(java.util.stream.Collectors.joining(System.lineSeparator())));
    String executable =
        Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
            .toString();
    var builder =
        new ProcessBuilder(executable, "@" + arguments)
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    var env = builder.environment();
    env.put("DATABASE_URL", POSTGRES.getJdbcUrl());
    env.put("DATABASE_USER", "ledgercore_runtime");
    env.put("DATABASE_PASSWORD", "test-runtime");
    env.remove("MIGRATION_USER");
    env.remove("MIGRATION_PASSWORD");
    env.remove("FLYWAY_USER");
    env.remove("FLYWAY_PASSWORD");
    env.put("SPRING_FLYWAY_ENABLED", "false");
    if (migration) {
      env.remove("DATABASE_USER");
      env.remove("DATABASE_PASSWORD");
      env.put("FLYWAY_USER", "ledgercore_migrator");
      env.put("FLYWAY_PASSWORD", "test-migration");
    }
    env.put("EVENTS_ENABLED", "false");
    env.put("RATE_LIMIT_ENABLED", "false");
    env.put("ADMIN_EMAIL", "");
    env.put("ADMIN_PASSWORD", "");
    Process child = builder.start();
    try {
      assertThat(child.waitFor(90, TimeUnit.SECONDS))
          .withFailMessage("Child timed out: %s", Files.readString(log))
          .isTrue();
      assertThat(child.exitValue())
          .withFailMessage("%s", Files.readString(log))
          .isEqualTo(expectedExit);
      if (migration) {
        assertThat(Files.readString(log))
            .contains("LEDGERCORE_MIGRATION_COMPLETED")
            .doesNotContain("Starting LedgerCoreApplication", "Tomcat started");
      }
      if (expectedExit == 91)
        assertThat(Files.readString(log)).contains("LEDGERCORE_PROCESS_HALT " + mode);
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        child.waitFor(10, TimeUnit.SECONDS);
      }
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }
}
