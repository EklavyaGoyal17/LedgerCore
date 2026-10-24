package com.ledgercore.ledger;

import com.ledgercore.common.Faults;
import com.ledgercore.ledger.domain.Journal;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class JournalPosting {
  private final JdbcTemplate db;
  private final Faults faults;
  private final ObservationRegistry observations;

  public JournalPosting(JdbcTemplate db, Faults faults, ObservationRegistry observations) {
    this.db = db;
    this.faults = faults;
    this.observations = observations;
  }

  public UUID post(String kind, UUID idempotency, UUID original, Journal journal) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Posting requires a transaction");
    return Observation.createNotStarted("ledgercore.journal.post", observations)
        .observe(() -> insert(kind, idempotency, original, journal));
  }

  private UUID insert(String kind, UUID idempotency, UUID original, Journal journal) {
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO journal_transactions(id,reference,kind,currency,idempotency_reference,original_journal_id) VALUES (?,?,?,?,?,?)",
        id,
        "LC-" + id,
        kind,
        journal.entries().getFirst().currency().name(),
        idempotency,
        original);
    for (Journal.Entry e : journal.entries()) {
      db.update(
          "INSERT INTO journal_entries(id,journal_id,ledger_account_id,debit_minor,credit_minor) VALUES (?,?,?,?,?)",
          UUID.randomUUID(),
          id,
          e.ledgerAccountId(),
          e.debit(),
          e.credit());
      faults.at(Faults.Point.DURING_JOURNAL);
    }
    return id;
  }
}
