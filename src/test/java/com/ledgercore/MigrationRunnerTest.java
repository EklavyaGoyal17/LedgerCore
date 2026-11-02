package com.ledgercore;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgercore.common.MigrationRunner;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MigrationRunnerTest {
  @ParameterizedTest
  @ValueSource(strings = {"DATABASE_URL", "FLYWAY_USER", "FLYWAY_PASSWORD"})
  void missingMigrationConfigurationFailsBeforeConnecting(String missing) {
    var environment =
        new HashMap<>(
            Map.of(
                "DATABASE_URL",
                "jdbc:postgresql://unused.invalid/ledgercore",
                "FLYWAY_USER",
                "migration-test",
                "FLYWAY_PASSWORD",
                "test-only"));
    environment.remove(missing);
    assertThatThrownBy(() -> MigrationRunner.run(environment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Migration-only process requires " + missing);
  }
}
