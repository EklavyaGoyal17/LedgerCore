package com.ledgercore;

import com.ledgercore.common.Faults;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

public class TestFaults extends Faults {
  private final AtomicReference<Point> next = new AtomicReference<>();

  public void failOnce(Point point) {
    next.set(point);
  }

  public void clear() {
    next.set(null);
  }

  @Override
  public void at(Point point) {
    if (next.compareAndSet(point, null)) throw new SimulatedCrash(point);
  }

  public static class SimulatedCrash extends RuntimeException {
    SimulatedCrash(Point point) {
      super("Test failure at " + point);
    }
  }

  @TestConfiguration
  public static class Configuration {
    @Bean
    @Primary
    TestFaults testFaults() {
      return new TestFaults();
    }
  }
}
