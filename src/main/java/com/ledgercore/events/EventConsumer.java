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
    if (event.eventId() == null || event.aggregateId() == null || event.eventType() == null)
      throw new IllegalArgumentException("Invalid event envelope");
    int inserted =
        db.update(
            "INSERT INTO processed_events(event_id,event_type,aggregate_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",
            event.eventId(),
            event.eventType(),
            event.aggregateId());
    if (inserted == 0) return;
    faults.at(Faults.Point.DURING_CONSUMER);
    db.update(
        "INSERT INTO event_audit_projection(event_id,event_type,aggregate_id) VALUES (?,?,?)",
        event.eventId(),
        event.eventType(),
        event.aggregateId());
  }
}
