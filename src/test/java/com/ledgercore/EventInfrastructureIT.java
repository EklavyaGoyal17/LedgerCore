package com.ledgercore;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.ledgercore.accounts.AccountService;
import com.ledgercore.common.Actor;
import com.ledgercore.common.Faults;
import com.ledgercore.events.*;
import com.ledgercore.ledger.domain.Money;
import com.ledgercore.security.RateLimiter;
import com.ledgercore.transfers.application.TransferEngine;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
      "spring.data.redis.timeout=3s"
    })
@ActiveProfiles("test")
@Import(TestFaults.Configuration.class)
@Testcontainers
@org.springframework.test.annotation.DirtiesContext
class EventInfrastructureIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.9.1"));

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
    KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    try {
      assertThat(engine.transfer(actor, "outage", a, b, Money.parse("100", "INR")).status())
          .isEqualTo(201);
      assertThat(publisher.publishOne()).isTrue();
      assertThat(
              db.queryForObject(
                  "SELECT count(*) FROM outbox_events WHERE attempts>0 AND published_at IS NULL",
                  Long.class))
          .isGreaterThanOrEqualTo(1);
    } finally {
      KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
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
                            "SELECT count(*) FROM event_audit_projection", Long.class))
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
            db.queryForObject("SELECT count(*) FROM transfers WHERE kind='TRANSFER'", Long.class))
        .isEqualTo(1);
    listeners.stop();
    listeners.start();
    assertThat(db.queryForObject("SELECT count(*) FROM processed_events", Long.class))
        .isEqualTo(db.queryForObject("SELECT count(*) FROM outbox_events", Long.class));
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
