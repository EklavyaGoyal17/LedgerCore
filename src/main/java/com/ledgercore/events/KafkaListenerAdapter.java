package com.ledgercore.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "ledgercore.events.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class KafkaListenerAdapter {
  private final EventConsumer consumer;

  public KafkaListenerAdapter(EventConsumer consumer) {
    this.consumer = consumer;
  }

  @KafkaListener(topics = "${ledgercore.events.topic}")
  public void receive(String value) {
    consumer.consume(value);
  }
}
