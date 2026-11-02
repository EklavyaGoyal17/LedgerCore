package com.ledgercore;

import com.ledgercore.common.MigrationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LedgerCoreApplication {
  public static void main(String[] args) {
    if (args.length == 1 && "--migrate-only".equals(args[0])) {
      MigrationRunner.run(System.getenv());
      return;
    }
    SpringApplication.run(LedgerCoreApplication.class, args);
  }
}
