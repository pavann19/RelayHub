package com.relayhub.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayhub.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "relayhub.kafka.enabled", havingValue = "true")
public class KafkaEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventBus.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${relayhub.kafka.topics.events:relayhub.events}")
    private String eventsTopic;

    @Value("${relayhub.kafka.topics.dlq:relayhub.events.dlq}")
    private String dlqTopic;

    public KafkaEventBus(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(Event event) {
        try {
            EventEnvelope envelope = EventEnvelope.fromEvent(event);
            String json = objectMapper.writeValueAsString(envelope);
            // Partition by tenantId for strict per-tenant in-order delivery
            kafkaTemplate.send(eventsTopic, event.tenantId(), json);
            log.info("KafkaEventBus: Published event {} to topic {} (key: {})", event.id(), eventsTopic, event.tenantId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event {} for Kafka: {}", event.id(), e.getMessage());
            throw new IllegalStateException("Event serialization failed", e);
        }
    }

    @Override
    public void publishDlq(Event event, String reason) {
        try {
            EventEnvelope envelope = EventEnvelope.fromEvent(event);
            String json = objectMapper.writeValueAsString(envelope);
            kafkaTemplate.send(dlqTopic, event.tenantId(), json);
            log.warn("KafkaEventBus [DLQ]: Event {} published to DLQ {}. Reason: {}", event.id(), dlqTopic, reason);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize DLQ event {}: {}", event.id(), e.getMessage());
        }
    }
}

