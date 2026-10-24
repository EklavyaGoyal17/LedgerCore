package com.ledgercore;

import static org.assertj.core.api.Assertions.*;

import com.ledgercore.common.DomainException;
import com.ledgercore.ledger.domain.Journal;
import com.ledgercore.ledger.domain.Money;
import com.ledgercore.transfers.application.Idempotency;
import com.ledgercore.transfers.domain.TransferLifecycle;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FinancialDomainTest {
  @Test
  void moneyIsExactAndCanonical() {
    assertThat(Money.parse("500.0", "INR")).isEqualTo(Money.parse("500.00", "INR"));
    assertThat(Money.parse("0.01", "USD").minor()).isEqualTo(1);
    assertThat(Money.parse("500", "INR").amount()).isEqualTo("500.00");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "0.001", "10000000000.01", "NaN", "1e100"})
  void rejectsInvalidAmounts(String amount) {
    assertThatThrownBy(() -> Money.parse(amount, "INR")).isInstanceOf(DomainException.class);
  }

  @Test
  void rejectsUnsupportedCurrency() {
    assertThatThrownBy(() -> Money.parse("1", "EUR")).isInstanceOf(DomainException.class);
  }

  @Test
  void validatesEachEntryAndWholeJournal() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(() -> new Journal.Entry(id, Money.Currency.INR, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Journal.Entry(id, Money.Currency.INR, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Journal(
                    List.of(
                        new Journal.Entry(id, Money.Currency.INR, 10, 0),
                        new Journal.Entry(id, Money.Currency.INR, 0, 9))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Journal(
                    List.of(
                        new Journal.Entry(id, Money.Currency.INR, 10, 0),
                        new Journal.Entry(id, Money.Currency.USD, 0, 10))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void generatedBalancedJournalsPreserveExactEquality() {
    Random random = new Random(7001);
    for (int i = 0; i < 1000; i++) {
      long a = 1 + random.nextLong(1_000_000_000), b = 1 + random.nextLong(1_000_000_000);
      assertThatCode(
              () ->
                  new Journal(
                      List.of(
                          new Journal.Entry(UUID.randomUUID(), Money.Currency.INR, a, 0),
                          new Journal.Entry(UUID.randomUUID(), Money.Currency.INR, b, 0),
                          new Journal.Entry(UUID.randomUUID(), Money.Currency.INR, 0, a + b))))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void terminalTransferCannotBeReopened() {
    var state = new TransferLifecycle();
    assertThatThrownBy(() -> state.transition(TransferLifecycle.State.COMPLETED))
        .isInstanceOf(IllegalStateException.class);
    state.transition(TransferLifecycle.State.PROCESSING);
    state.transition(TransferLifecycle.State.COMPLETED);
    assertThatThrownBy(() -> state.transition(TransferLifecycle.State.FAILED))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void fingerprintsAreStableAndDistinguishRequests() {
    assertThat(Idempotency.fingerprint("TRANSFER|100|INR"))
        .hasSize(64)
        .isEqualTo(Idempotency.fingerprint("TRANSFER|100|INR"))
        .isNotEqualTo(Idempotency.fingerprint("TRANSFER|101|INR"));
  }
}
