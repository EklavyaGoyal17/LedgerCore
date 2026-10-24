package com.ledgercore.ledger.domain;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

public record Journal(List<Entry> entries) {
  public record Entry(UUID ledgerAccountId, Money.Currency currency, long debit, long credit) {
    public Entry {
      if (ledgerAccountId == null
          || currency == null
          || debit < 0
          || credit < 0
          || (debit == 0) == (credit == 0)) {
        throw new IllegalArgumentException("An entry must have exactly one positive side");
      }
    }
  }

  public Journal {
    entries = List.copyOf(entries);
    if (entries.size() < 2)
      throw new IllegalArgumentException("A journal needs two or more entries");
    BigInteger net = BigInteger.ZERO;
    Money.Currency currency = entries.getFirst().currency();
    for (Entry e : entries) {
      if (e.currency() != currency) throw new IllegalArgumentException("Mixed journal currencies");
      net = net.add(BigInteger.valueOf(e.debit())).subtract(BigInteger.valueOf(e.credit()));
    }
    if (net.signum() != 0) throw new IllegalArgumentException("Unbalanced journal");
  }
}
