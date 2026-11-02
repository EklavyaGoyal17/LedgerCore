package com.ledgercore.events;

import com.ledgercore.common.Actor;
import com.ledgercore.common.DomainException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable recovery queue. Manual replay affects the audit projection, never financial posting. */
@Service
public class EventOperations {
  public record FailedEvent(
      UUID id,
      String topic,
      int partition,
      long offset,
      String payload,
      String errorCategory,
      Instant quarantinedAt,
      Instant resolvedAt) {}

  private static final class ReplayFailure extends RuntimeException {
    ReplayFailure(RuntimeException cause) {
      super(cause);
    }
  }

  private final JdbcTemplate db;
  private final EventConsumer consumer;
  private final TransactionTemplate tx;
  private final MeterRegistry metrics;

  public EventOperations(
      JdbcTemplate db,
      EventConsumer consumer,
      PlatformTransactionManager manager,
      MeterRegistry metrics) {
    this.db = db;
    this.consumer = consumer;
    this.tx = new TransactionTemplate(manager);
    this.metrics = metrics;
    metrics.gauge(
        "ledgercore.events.quarantined",
        this,
        service ->
            service.db.queryForObject(
                "SELECT count(*) FROM failed_kafka_events WHERE resolved_at IS NULL", Long.class));
  }

  @Transactional
  public void quarantine(ConsumerRecord<?, ?> record, Exception failure) {
    db.update(
        """
        INSERT INTO failed_kafka_events(id,topic,partition_number,offset_number,payload,error_category)
        VALUES (?,?,?,?,?,?) ON CONFLICT(topic,partition_number,offset_number) DO NOTHING
        """,
        UUID.randomUUID(),
        record.topic(),
        record.partition(),
        record.offset(),
        record.value() == null ? null : record.value().toString(),
        failure.getClass().getSimpleName());
    metrics.counter("ledgercore.events.quarantine.deliveries").increment();
  }

  @Transactional(readOnly = true)
  public List<FailedEvent> list(Actor actor, int limit, int offset) {
    requireAdmin(actor);
    if (limit < 1 || limit > 100 || offset < 0)
      throw new DomainException(400, "INVALID_PAGE", "Limit must be 1–100 and offset nonnegative");
    return db.query(
        "SELECT * FROM failed_kafka_events WHERE resolved_at IS NULL ORDER BY quarantined_at,id LIMIT ? OFFSET ?",
        (r, n) -> map(r),
        limit,
        offset);
  }

  @Transactional(readOnly = true)
  public FailedEvent get(Actor actor, UUID id) {
    requireAdmin(actor);
    return find(id, false);
  }

  public FailedEvent replay(Actor actor, UUID id, String replacementPayload, String reason) {
    requireAdmin(actor);
    if (reason == null
        || reason.isBlank()
        || reason.length() > 500
        || (replacementPayload != null
            && (replacementPayload.isBlank() || replacementPayload.length() > 65536)))
      throw new DomainException(
          400,
          "INVALID_REPLAY",
          "Supply a reason and an optional nonblank payload of at most 65536 characters");
    try {
      return tx.execute(
          status -> {
            FailedEvent event = find(id, true);
            if (event.resolvedAt() != null) return event;
            String payload = replacementPayload == null ? event.payload() : replacementPayload;
            try {
              consumer.consume(payload);
            } catch (RuntimeException failure) {
              throw new ReplayFailure(failure);
            }
            db.update(
                "UPDATE failed_kafka_events SET resolved_at=clock_timestamp() WHERE id=?", id);
            audit(actor, id, reason, payload, "SUCCEEDED", null);
            return find(id, false);
          });
    } catch (ReplayFailure failure) {
      // The consumer transaction has rolled back, including its deduplication marker.
      // Persist the failed operator attempt in a new transaction after that rollback.
      tx.executeWithoutResult(
          status -> {
            FailedEvent event = find(id, false);
            audit(
                actor,
                id,
                reason,
                replacementPayload == null ? event.payload() : replacementPayload,
                "FAILED",
                failure.getCause().getClass().getSimpleName());
          });
      throw new DomainException(
          422,
          "EVENT_REPLAY_FAILED",
          "Event was not processed; quarantine and replay audit are retained");
    }
  }

  private void audit(
      Actor actor, UUID id, String reason, String payload, String outcome, String category) {
    db.update(
        "INSERT INTO event_replay_attempts(id,failed_event_id,actor_id,reason,replay_payload,outcome,error_category) VALUES (?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        actor.id(),
        reason,
        payload,
        outcome,
        category);
  }

  private FailedEvent find(UUID id, boolean lock) {
    var rows =
        db.query(
            "SELECT * FROM failed_kafka_events WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            (r, n) -> map(r),
            id);
    if (rows.isEmpty())
      throw new DomainException(404, "EVENT_NOT_FOUND", "Quarantined event not found");
    return rows.getFirst();
  }

  private FailedEvent map(java.sql.ResultSet r) throws java.sql.SQLException {
    var resolved = r.getTimestamp("resolved_at");
    return new FailedEvent(
        r.getObject("id", UUID.class),
        r.getString("topic"),
        r.getInt("partition_number"),
        r.getLong("offset_number"),
        r.getString("payload"),
        r.getString("error_category"),
        r.getTimestamp("quarantined_at").toInstant(),
        resolved == null ? null : resolved.toInstant());
  }

  private void requireAdmin(Actor actor) {
    if (!actor.admin())
      throw new DomainException(403, "FORBIDDEN", "Administrator role is required");
  }
}
