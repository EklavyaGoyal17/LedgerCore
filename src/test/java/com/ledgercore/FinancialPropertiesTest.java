package com.ledgercore;

import static org.assertj.core.api.Assertions.*;

import com.ledgercore.ledger.domain.Journal;
import com.ledgercore.ledger.domain.Money;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.*;
import net.jqwik.api.constraints.LongRange;

/** Generated examples and shrinking complement the real PostgreSQL sequence tests. */
class FinancialPropertiesTest {
  private static final UUID A = new UUID(0, 10), B = new UUID(0, 11), C = new UUID(0, 12);

  @Property(tries = 1000)
  void formattingAndParsingNeverLoseMinorUnits(
      @ForAll @LongRange(min = 1, max = 1000000000000L) long minor,
      @ForAll Money.Currency currency) {
    Money original = new Money(minor, currency);
    assertThat(Money.parse(original.amount(), currency.name())).isEqualTo(original);
  }

  @Property(tries = 1000)
  void splitCounterpartsBalanceExactlyAndRejectOneMinorUnitMissing(
      @ForAll @LongRange(min = 1, max = 1000000000000L) long first,
      @ForAll @LongRange(min = 1, max = 1000000000000L) long second,
      @ForAll Money.Currency currency) {
    assertThatCode(
            () ->
                new Journal(
                    List.of(
                        new Journal.Entry(A, currency, first, 0),
                        new Journal.Entry(B, currency, second, 0),
                        new Journal.Entry(C, currency, 0, first + second))))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                new Journal(
                    List.of(
                        new Journal.Entry(A, currency, first, 0),
                        new Journal.Entry(B, currency, second, 0),
                        new Journal.Entry(C, currency, 0, first + second - 1))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Property(tries = 1000)
  void fractionalMinorUnitsAreAlwaysRejected(
      @ForAll @LongRange(min = 1, max = 1000000000) long wholeMinor) {
    String fractional =
        java.math.BigDecimal.valueOf(wholeMinor, 2)
            .add(new java.math.BigDecimal("0.001"))
            .toPlainString();
    assertThatThrownBy(() -> Money.parse(fractional, "INR"))
        .isInstanceOf(com.ledgercore.common.DomainException.class);
  }
}
