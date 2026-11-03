package com.relayhub.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayhub.dispatcher.WebhookDispatcher;
import com.relayhub.model.Event;
import com.relayhub.model.EventStatus;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "relayhub.kafka.enabled", havingValue = "true")
public class KafkaEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventConsumer.class);

    private final WebhookDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public KafkaEventConsumer(WebhookDispatcher dispatcher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${relayhub.kafka.topics.events:relayhub.events}",
            groupId = "${relayhub.kafka.consumer.group:relayhub-dispatcher-group}"
    )
    public void onEventReceived(String message) {
        try {
            EventEnvelope envelope = objectMapper.readValue(message, EventEnvelope.class);
            log.info("Kafka consumer received event {} (type: {})", envelope.eventId(), envelope.eventType());

            Event event = new Event(
                    envelope.eventId(),
                    envelope.tenantId(),
                    envelope.eventType(),
                    envelope.idempotencyKey(),
                    envelope.payload(),
                    EventStatus.PROCESSING,
                    Instant.ofEpochSecond(envelope.timestamp())
            );

            dispatcher.dispatch(event);
        } catch (Exception e) {
            log.error("Failed to process received Kafka message: {}", e.getMessage(), e);
        }
    }
}

