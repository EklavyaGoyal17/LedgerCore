package com.ledgercore.ledger.domain;

import com.ledgercore.common.DomainException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Wire amounts are decimal strings. Accounting uses exact integer minor units. */
public record Money(long minor, Currency currency) {
  public enum Currency {
    INR,
    USD
  }

  public Money {
    if (minor <= 0 || minor > 1_000_000_000_000L || currency == null) {
      throw new DomainException(
          400, "INVALID_AMOUNT", "Amount must be positive and at most 10000000000.00");
    }
  }

  public static Money parse(String amount, String currency) {
    try {
      BigDecimal decimal = new BigDecimal(amount);
      return new Money(
          decimal.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact(),
          Currency.valueOf(currency));
    } catch (IllegalArgumentException | ArithmeticException ex) {
      throw new DomainException(
          400,
          "INVALID_MONEY",
          "Use INR or USD and an exact amount with at most two decimal places");
    }
  }

  public String amount() {
    return format(minor);
  }

  public static String format(long minor) {
    return BigDecimal.valueOf(minor, 2).toPlainString();
  }
}
