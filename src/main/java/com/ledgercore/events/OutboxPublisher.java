package com.ledgercore.events;

import com.ledgercore.common.Faults;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(
    name = "ledgercore.events.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class OutboxPublisher {
  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  private record Pending(
      UUID id, UUID aggregate, String payload, String traceparent, int attempts) {}

  private final JdbcTemplate db;
  private final KafkaTemplate<String, String> kafka;
  private final Faults faults;
  private final MeterRegistry metrics;
  private final TransactionTemplate tx;
  private final String topic;
  private final Tracer tracer;
  private final Propagator propagator;

  public OutboxPublisher(
      JdbcTemplate db,
      KafkaTemplate<String, String> kafka,
      Faults faults,
      MeterRegistry metrics,
      Tracer tracer,
      Propagator propagator,
      PlatformTransactionManager manager,
      @Value("${ledgercore.events.topic}") String topic) {
    this.db = db;
    this.kafka = kafka;
    this.faults = faults;
    this.metrics = metrics;
    this.tracer = tracer;
    this.propagator = propagator;
    this.tx = new TransactionTemplate(manager);
    this.topic = topic;
    metrics.gauge(
        "ledgercore.outbox.pending",
        this,
        p ->
            p.db.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class));
  }

  @Scheduled(fixedDelayString = "${ledgercore.events.poll-ms}")
  public void poll() {
    for (int i = 0; i < 25; i++) if (!publishOne()) break;
  }

  public boolean publishOne() {
    return Boolean.TRUE.equals(
        tx.execute(
            status -> {
              var rows =
                  db.query(
                      "SELECT id,aggregate_id,payload::text,traceparent,attempts FROM outbox_events WHERE published_at IS NULL AND next_attempt_at<=clock_timestamp() ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
                      (r, n) ->
                          new Pending(
                              r.getObject(1, UUID.class),
                              r.getObject(2, UUID.class),
                              r.getString(3),
                              r.getString(4),
                              r.getInt(5)));
              if (rows.isEmpty()) return false;
              Pending event = rows.getFirst();
              Span span =
                  (event.traceparent() == null
                          ? tracer.spanBuilder()
                          : propagator.extract(
                              event.traceparent(),
                              (carrier, key) -> "traceparent".equals(key) ? carrier : null))
                      .name("ledgercore.outbox.publish")
                      .start();
              try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
                faults.at(Faults.Point.BEFORE_PUBLISH);
                ProducerRecord<String, String> record =
                    new ProducerRecord<>(topic, event.aggregate().toString(), event.payload());
                kafka.send(record).get(12, TimeUnit.SECONDS);
                faults.at(Faults.Point.AFTER_PUBLISH);
                db.update(
                    "UPDATE outbox_events SET published_at=clock_timestamp(),attempts=attempts+1 WHERE id=?",
                    event.id());
              } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Outbox publisher interrupted", ex);
              } catch (Exception ex) {
                // Acknowledgment can be lost. Keep pending; redelivery is expected and harmless to
                // the consumer.
                int delay = Math.min(300, 1 << Math.min(event.attempts(), 8));
                db.update(
                    "UPDATE outbox_events SET attempts=attempts+1,next_attempt_at=clock_timestamp()+(? * interval '1 second') WHERE id=?",
                    delay,
                    event.id());
                metrics.counter("ledgercore.outbox.publication.failures").increment();
                log.warn(
                    "Event publication unconfirmed; eventId={} errorCategory={}",
                    event.id(),
                    ex.getClass().getSimpleName());
              } finally {
                span.end();
              }
              return true;
            }));
  }
}
