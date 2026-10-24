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
  DefaultErrorHandler eventErrors() {
    // Never silently skip a failed event. A poison event needs an operator repair/replay.
    DefaultErrorHandler handler =
        new DefaultErrorHandler(new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
    handler.setClassifications(java.util.Map.of(Exception.class, true), true);
    return handler;
  }
}
