package com.ledgercore.common;

import java.util.Map;
import org.flywaydb.core.Flyway;

/** Dedicated deployment process: no HTTP server, Spring context, or runtime datasource. */
public final class MigrationRunner {
  private MigrationRunner() {}

  public static void run(Map<String, String> environment) {
    Flyway.configure()
        .dataSource(
            required(environment, "DATABASE_URL"),
            required(environment, "FLYWAY_USER"),
            required(environment, "FLYWAY_PASSWORD"))
        .load()
        .migrate();
    System.out.println("LEDGERCORE_MIGRATION_COMPLETED");
  }

  private static String required(Map<String, String> environment, String name) {
    String value = environment.get(name);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Migration-only process requires " + name);
    return value;
  }
}
