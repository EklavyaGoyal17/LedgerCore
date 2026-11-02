package com.ledgercore.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@ConditionalOnProperty(
    name = "ledgercore.events.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class KafkaConfiguration {
  @Bean
  NewTopic eventsTopic(@Value("${ledgercore.events.topic}") String topic) {
    return TopicBuilder.name(topic).partitions(3).replicas(1).build();
  }

  @Bean
  DefaultErrorHandler eventErrors(
      EventOperations operations,
      @Value("${ledgercore.events.consumer-retry-ms:1000}") long retryMs,
      @Value("${ledgercore.events.consumer-retries:3}") long retries) {
    if (retryMs < 0 || retries < 0)
      throw new IllegalArgumentException("Invalid Kafka retry limits");
    // Advancing past a poison record is safe only when quarantine has durably committed.
    // If PostgreSQL is unavailable, recovery itself throws and the record is retried.
    DefaultErrorHandler handler =
        new DefaultErrorHandler(operations::quarantine, new FixedBackOff(retryMs, retries));
    handler.setClassifications(java.util.Map.of(Exception.class, true), true);
    return handler;
  }
}
