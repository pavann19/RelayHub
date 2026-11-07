package com.relayhub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaReliabilityConfig {
    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        // Never silently recover/commit a record that failed durable materialization.
        DefaultErrorHandler handler = new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setAckAfterHandle(false);
        return handler;
    }
}
