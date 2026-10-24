package com.ledgercore.common;

import org.springframework.stereotype.Component;

/** Tests replace this no-op bean. There is deliberately no public fault injection API. */
@Component
public class Faults {
  public enum Point {
    BEFORE_TRANSACTION,
    AFTER_VALIDATION,
    DURING_JOURNAL,
    AFTER_OUTBOX,
    BEFORE_COMMIT,
    AFTER_COMMIT,
    BEFORE_PUBLISH,
    AFTER_PUBLISH,
    DURING_CONSUMER
  }

  public void at(Point point) {}
}
