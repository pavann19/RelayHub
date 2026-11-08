package com.relayhub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaReliabilityConfig {
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        // Commit the failed offset only after its original record is durably quarantined.
        DefaultErrorHandler handler = new DefaultErrorHandler((record, error) -> {
            jdbc.update("""
                    INSERT INTO kafka_quarantine(topic, partition_id, record_offset, payload, error_type)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """, record.topic(), record.partition(), record.offset(),
                    record.value() == null ? null : record.value().toString(), error.getClass().getSimpleName());
        }, new FixedBackOff(1000L, 2));
        handler.setAckAfterHandle(true);
        return handler;
    }
}
