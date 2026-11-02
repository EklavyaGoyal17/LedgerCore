package com.ledgercore.events;

import com.ledgercore.common.Faults;
import com.ledgercore.common.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventConsumer {
  private final JdbcTemplate db;
  private final Json json;
  private final Faults faults;

  public EventConsumer(JdbcTemplate db, Json json, Faults faults) {
    this.db = db;
    this.json = json;
    this.faults = faults;
  }

  @Transactional
  public void consume(String payload) {
    var event = json.read(payload, EventRecorder.Envelope.class);
    if (event == null
        || event.eventId() == null
        || event.aggregateId() == null
        || event.eventType() == null
        || event.eventType().isBlank()
        || event.eventType().length() > 50
        || event.occurredAt() == null
        || event.correlationId() == null
        || event.correlationId().isBlank()
        || event.correlationId().length() > 64)
      throw new IllegalArgumentException("Invalid event envelope");
    int inserted =
        db.update(
            "INSERT INTO processed_events(event_id,event_type,aggregate_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",
            event.eventId(),
            event.eventType(),
            event.aggregateId());
    if (inserted == 0) {
      Boolean sameIdentity =
          db.queryForObject(
              "SELECT event_type=? AND aggregate_id=? FROM processed_events WHERE event_id=?",
              Boolean.class,
              event.eventType(),
              event.aggregateId(),
              event.eventId());
      if (!Boolean.TRUE.equals(sameIdentity))
        throw new IllegalArgumentException("Event ID reused for a different event identity");
      return;
    }
    faults.at(Faults.Point.DURING_CONSUMER);
    db.update(
        "INSERT INTO event_audit_projection(event_id,event_type,aggregate_id) VALUES (?,?,?)",
        event.eventId(),
        event.eventType(),
        event.aggregateId());
  }
}
