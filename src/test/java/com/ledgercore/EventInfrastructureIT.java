package com.ledgercore;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgercore.accounts.AccountService;
import com.ledgercore.common.Actor;
import com.ledgercore.common.Faults;
import com.ledgercore.events.*;
import com.ledgercore.ledger.domain.Money;
import com.ledgercore.security.RateLimiter;
import com.ledgercore.transfers.application.TransferEngine;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    properties = {
      "ledgercore.events.enabled=true",
      "ledgercore.events.poll-ms=3600000",
      "ledgercore.rate-limit.requests-per-minute=3",
      "spring.data.redis.timeout=3s",
      "ledgercore.events.consumer-retry-ms=100",
      "ledgercore.events.consumer-retries=2"
    })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestFaults.Configuration.class)
@Testcontainers
@org.springframework.test.annotation.DirtiesContext
class EventInfrastructureIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");

  @Container static final KafkaContainer KAFKA = new PersistentBroker();

  static class PersistentBroker extends KafkaContainer {
    PersistentBroker() {
      super(DockerImageName.parse("apache/kafka-native:3.9.1"));
      // Docker may reassign a dynamically published port on container restart. Select a free
      // port before creation, then bind it explicitly so the advertised listener remains valid.
      try (ServerSocket port = new ServerSocket(0)) {
        addFixedExposedPort(port.getLocalPort(), 9092);
      } catch (IOException failure) {
        throw new IllegalStateException("Cannot allocate broker restart test port", failure);
      }
    }
  }

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4.5-alpine")).withExposedPorts(6379);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    r.add("spring.data.redis.host", REDIS::getHost);
    r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired JdbcTemplate db;
  @Autowired AccountService accounts;
  @Autowired TransferEngine engine;
  @Autowired OutboxPublisher publisher;
  @Autowired EventConsumer consumer;
  @Autowired TestFaults faults;
  @Autowired KafkaListenerEndpointRegistry listeners;
  @Autowired RateLimiter limiter;
  @Autowired StringRedisTemplate redis;
  @Autowired EventOperations operations;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired com.ledgercore.common.Json json;
  @Autowired MockMvc mvc;

  @Test
  void kafkaOutageAcknowledgmentLossRedeliveryAndConsumerRecovery() throws Exception {
    UUID uid = UUID.randomUUID();
    db.update(
        "INSERT INTO app_users(id,email,password_hash,role) VALUES (?,?,?,'ADMIN')",
        uid,
        uid + "@test.invalid",
        "fixture");
    Actor actor = new Actor(uid, true);
    UUID a = accounts.create(actor, "INR").id(), b = accounts.create(actor, "INR").id();
    engine.deposit(actor, "fund", a, Money.parse("1000", "INR"));
    listeners.stop();
    // Stop/start the same broker container: this actually terminates Kafka while retaining its log.
    String brokerId = KAFKA.getContainerId();
    KAFKA.getDockerClient().stopContainerCmd(brokerId).withTimeout(5).exec();
    try {
      assertThat(engine.transfer(actor, "outage", a, b, Money.parse("100", "INR")).status())
          .isEqualTo(201);
      assertThat(publisher.publishOne()).isTrue();
      int attemptsBeforePoll =
          db.queryForObject("SELECT sum(attempts) FROM outbox_events", Integer.class);
      publisher.poll();
      assertThat(db.queryForObject("SELECT sum(attempts) FROM outbox_events", Integer.class))
          .isEqualTo(attemptsBeforePoll + 1);
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM outbox_events WHERE attempts>0 AND published_at IS NULL",
                  Long.class))
          .isGreaterThanOrEqualTo(1);
    } finally {
      KAFKA.getDockerClient().startContainerCmd(brokerId).exec();
    }
    try (var admin =
        AdminClient.create(
            Map.of(
                "bootstrap.servers",
                KAFKA.getBootstrapServers(),
                "request.timeout.ms",
                2000,
                "default.api.timeout.ms",
                3000))) {
      await()
          .atMost(Duration.ofSeconds(45))
          .until(
              () -> {
                try {
                  return admin.describeCluster().nodes().get(3, TimeUnit.SECONDS).size() == 1;
                } catch (Exception startupInProgress) {
                  return false;
                }
              });
    }
    db.update("UPDATE outbox_events SET next_attempt_at=clock_timestamp()");
    faults.failOnce(Faults.Point.AFTER_PUBLISH);
    publisher.publishOne();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE attempts>0 AND published_at IS NULL",
                Long.class))
        .isGreaterThanOrEqualTo(1);
    faults.failOnce(Faults.Point.DURING_CONSUMER);
    listeners.start();
    db.update("UPDATE outbox_events SET next_attempt_at=clock_timestamp()");
    while (publisher.publishOne()) {
      /* drain pending */
    }
    await()
        .atMost(Duration.ofSeconds(45))
        .untilAsserted(
            () ->
                assertThat(
                        db.queryForObject(
                            "SELECT count(*) FROM event_audit_projection p JOIN outbox_events o ON o.id=p.event_id",
                            Long.class))
                    .isEqualTo(
                        db.queryForObject("SELECT count(*) FROM outbox_events", Long.class)));
    String payload =
        db.queryForObject(
            "SELECT payload::text FROM outbox_events WHERE aggregate_id=?", String.class, b);
    consumer.consume(payload);
    consumer.consume(payload);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM event_audit_projection WHERE aggregate_id=?", Long.class, b))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT balance_minor FROM financial_accounts WHERE id=?", Long.class, a))
        .isEqualTo(90000);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM transfers WHERE kind='TRANSFER' AND source_id=?",
                Long.class,
                a))
        .isEqualTo(1);
    listeners.stop();
    listeners.start();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM processed_events p JOIN outbox_events o ON o.id=p.event_id",
                Long.class))
        .isEqualTo(db.queryForObject("SELECT count(*) FROM outbox_events", Long.class));
  }

  @Test
  void poisonEventIsDurablyQuarantinedPartitionContinuesAndAdminReplayIsAudited() throws Exception {
    UUID uid = UUID.randomUUID();
    db.update(
        "INSERT INTO app_users(id,email,password_hash,role) VALUES (?,?,?,'ADMIN')",
        uid,
        uid + "@test.invalid",
        "fixture");
    Actor admin = new Actor(uid, true);
    // The recoverer must propagate durable-store failures so Kafka cannot acknowledge a lost
    // record.
    assertThatThrownBy(
            () ->
                operations.quarantine(
                    new ConsumerRecord<>("x".repeat(250), 0, 0, "fixture", "payload"),
                    new IllegalArgumentException()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    mvc.perform(get("/api/v1/admin/events/quarantine")).andExpect(status().isUnauthorized());
    mvc.perform(
            get("/api/v1/admin/events/quarantine")
                .with(
                    jwt()
                        .jwt(j -> j.subject(uid.toString()).claim("role", "USER"))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/admin/events/quarantine")
                .with(
                    jwt()
                        .jwt(j -> j.subject(uid.toString()).claim("role", "ADMIN"))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk());
    String poison = "malformed-event-" + UUID.randomUUID();
    UUID nextEvent = UUID.randomUUID();
    UUID replayedEvent = UUID.randomUUID();
    String valid =
        json.write(
            new EventRecorder.Envelope(
                nextEvent, "AccountCreated", UUID.randomUUID(), Instant.now(), "poison-recovery"));
    listeners.start();
    var metadata =
        kafka
            .send(new ProducerRecord<>("ledgercore.events.v1", 0, "poison", poison))
            .get(20, TimeUnit.SECONDS)
            .getRecordMetadata();
    kafka
        .send(new ProducerRecord<>("ledgercore.events.v1", 0, "following", valid))
        .get(20, TimeUnit.SECONDS);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              assertThat(
                      db.queryForObject(
                          "SELECT count(*) FROM failed_kafka_events WHERE payload=?",
                          Long.class,
                          poison))
                  .isEqualTo(1);
              assertThat(
                      db.queryForObject(
                          "SELECT count(*) FROM event_audit_projection WHERE event_id=?",
                          Long.class,
                          nextEvent))
                  .isEqualTo(1);
            });
    UUID id =
        db.queryForObject("SELECT id FROM failed_kafka_events WHERE payload=?", UUID.class, poison);
    // A restart between quarantine commit and Kafka offset commit may invoke recovery again.
    operations.quarantine(
        new ConsumerRecord<>(
            metadata.topic(), metadata.partition(), metadata.offset(), "poison", poison),
        new IllegalArgumentException());
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM failed_kafka_events WHERE id=?", Long.class, id))
        .isEqualTo(1);
    assertThatThrownBy(() -> operations.get(new Actor(uid, false), id))
        .isInstanceOf(com.ledgercore.common.DomainException.class);
    assertThatThrownBy(() -> operations.list(admin, 101, 0))
        .isInstanceOf(com.ledgercore.common.DomainException.class);
    assertThat(operations.list(admin, 25, 0))
        .extracting(EventOperations.FailedEvent::id)
        .contains(id);
    assertThatThrownBy(() -> operations.replay(admin, id, null, "Verify original poison event"))
        .isInstanceOf(com.ledgercore.common.DomainException.class);
    assertThat(operations.get(admin, id).resolvedAt()).isNull();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM event_replay_attempts WHERE failed_event_id=? AND outcome='FAILED'",
                Long.class,
                id))
        .isEqualTo(1);
    String repaired =
        json.write(
            new EventRecorder.Envelope(
                replayedEvent, "AccountCreated", UUID.randomUUID(), Instant.now(), "repair"));
    faults.failOnce(Faults.Point.DURING_CONSUMER);
    assertThatThrownBy(
            () ->
                operations.replay(
                    admin, id, repaired, "Exercise rollback before successful replay"))
        .isInstanceOf(com.ledgercore.common.DomainException.class);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM processed_events WHERE event_id=?",
                Long.class,
                replayedEvent))
        .isZero();
    assertThat(
            operations
                .replay(admin, id, repaired, "Correct malformed payload after investigation")
                .resolvedAt())
        .isNotNull();
    assertThat(operations.get(admin, id).payload()).isEqualTo(poison);
    operations.replay(admin, id, repaired, "Retry after client response loss");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM event_audit_projection WHERE event_id=?",
                Long.class,
                replayedEvent))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM event_replay_attempts WHERE failed_event_id=? AND outcome='SUCCEEDED'",
                Long.class,
                id))
        .isEqualTo(1);
    String conflictingIdentity =
        json.write(
            new EventRecorder.Envelope(
                replayedEvent,
                "TransferCompleted",
                UUID.randomUUID(),
                Instant.now(),
                "invalid-duplicate"));
    assertThatThrownBy(() -> consumer.consume(conflictingIdentity))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> db.update("UPDATE failed_kafka_events SET payload='rewritten' WHERE id=?", id))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () -> db.update("DELETE FROM event_replay_attempts WHERE failed_event_id=?", id))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void redisUsesAtomicExpiryAndDocumentedOutagePolicies() {
    String identity = "user:" + UUID.randomUUID();
    assertThat(
            redis.execute(
                (org.springframework.data.redis.core.RedisCallback<String>)
                    connection -> connection.ping()))
        .isEqualTo("PONG");
    assertThat(limiter.allow(identity, false)).isTrue();
    assertThat(limiter.allow(identity, false)).isTrue();
    assertThat(limiter.allow(identity, false)).isTrue();
    boolean fourth = limiter.allow(identity, false);
    String key =
        "ledgercore:rate:" + com.ledgercore.transfers.application.Idempotency.fingerprint(identity);
    assertThat(redis.opsForValue().get(key)).isEqualTo("4");
    assertThat(fourth).isFalse();
    assertThat(redis.getExpire(key)).isBetween(1L, 60L);
    REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    try {
      assertThat(limiter.allow("business", false)).isTrue();
      assertThat(limiter.allow("authentication", true)).isFalse();
    } finally {
      REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }
  }
}
