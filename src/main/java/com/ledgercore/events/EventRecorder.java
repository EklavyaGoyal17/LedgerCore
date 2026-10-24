package com.ledgercore.events;

import com.ledgercore.common.Json;
import io.micrometer.tracing.Tracer;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventRecorder {
  public record Envelope(
      UUID eventId, String eventType, UUID aggregateId, Instant occurredAt, String correlationId) {}

  private final JdbcTemplate db;
  private final Json json;
  private final Tracer tracer;

  public EventRecorder(JdbcTemplate db, Json json, Tracer tracer) {
    this.db = db;
    this.json = json;
    this.tracer = tracer;
  }

  public void record(UUID actor, String type, UUID resource) {
    String correlation = MDC.get("correlationId");
    if (correlation == null) correlation = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO audit_events(id,actor_id,action,resource_id,correlation_id,metadata) VALUES (?,?,?,?,?,?::jsonb)",
        UUID.randomUUID(),
        actor,
        type,
        resource,
        correlation,
        json.write(Map.of("simulation", true)));
    UUID eventId = UUID.randomUUID();
    String traceparent = null;
    if (tracer.currentSpan() != null) {
      var ctx = tracer.currentSpan().context();
      traceparent =
          "00-"
              + ctx.traceId()
              + "-"
              + ctx.spanId()
              + "-"
              + (Boolean.TRUE.equals(ctx.sampled()) ? "01" : "00");
    }
    db.update(
        "INSERT INTO outbox_events(id,aggregate_id,event_type,payload,traceparent) VALUES (?,?,?,?::jsonb,?)",
        eventId,
        resource,
        type,
        json.write(new Envelope(eventId, type, resource, Instant.now(), correlation)),
        traceparent);
  }
}
