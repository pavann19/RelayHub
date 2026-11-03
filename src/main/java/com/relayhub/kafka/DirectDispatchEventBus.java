package com.relayhub.kafka;

import com.relayhub.dispatcher.WebhookDispatcher;
import com.relayhub.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@ConditionalOnProperty(name = "relayhub.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class DirectDispatchEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(DirectDispatchEventBus.class);
    private final ObjectProvider<WebhookDispatcher> dispatcherProvider;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DirectDispatchEventBus(ObjectProvider<WebhookDispatcher> dispatcherProvider) {
        this.dispatcherProvider = dispatcherProvider;
    }

    @Override
    public void publish(Event event) {
        log.info("DirectDispatchEventBus: Ingested event {} (type: {}) queued for dispatch", event.id(), event.eventType());
        executor.submit(() -> {
            try {
                WebhookDispatcher dispatcher = dispatcherProvider.getIfAvailable();
                if (dispatcher != null) {
                    dispatcher.dispatch(event);
                } else {
                    log.error("WebhookDispatcher not available for event {}", event.id());
                }
            } catch (Exception e) {
                log.error("Error executing direct dispatch for event {}: {}", event.id(), e.getMessage(), e);
            }
        });
    }

    @Override
    public void publishDlq(Event event, String reason) {
        log.warn("DirectDispatchEventBus [DLQ]: Event {} routed to Dead-Letter Queue. Reason: {}", event.id(), reason);
    }
}

