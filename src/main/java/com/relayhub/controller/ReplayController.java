package com.relayhub.controller;

import com.relayhub.dispatcher.WebhookDispatcher;
import com.relayhub.dto.ReplayRequest;
import com.relayhub.dto.ReplayResponse;
import com.relayhub.model.Event;
import com.relayhub.model.Tenant;
import com.relayhub.model.WebhookDelivery;
import com.relayhub.repository.DeliveryRepository;
import com.relayhub.repository.EventRepository;
import com.relayhub.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/deliveries/replay")
@Tag(name = "Replay & Redrive API", description = "Re-drive dead-lettered, failed, or missed webhook events on-demand.")
public class ReplayController {

    private static final Logger log = LoggerFactory.getLogger(ReplayController.class);

    private final DeliveryRepository deliveryRepository;
    private final EventRepository eventRepository;
    private final WebhookDispatcher dispatcher;

    public ReplayController(
            DeliveryRepository deliveryRepository,
            EventRepository eventRepository,
            WebhookDispatcher dispatcher
    ) {
        this.deliveryRepository = deliveryRepository;
        this.eventRepository = eventRepository;
        this.dispatcher = dispatcher;
    }

    @PostMapping
    @Operation(summary = "Replay dead-lettered or failed webhook events")
    public ResponseEntity<ReplayResponse> replayDeliveries(@RequestBody(required = false) ReplayRequest request) {
        if (request != null && request.subscriptionId() != null && !request.subscriptionId().isBlank()) {
            throw new IllegalArgumentException("subscriptionId replay filtering is not supported");
        }
        Tenant tenant = TenantContext.getTenant();
        String tenantId = tenant != null ? tenant.id() : "tenant_default";

        List<String> replayedEventIds = new ArrayList<>();

        // Case 1: Replay by specific Delivery ID
        if (request != null && request.deliveryId() != null && !request.deliveryId().isBlank()) {
            WebhookDelivery delivery = deliveryRepository.findById(request.deliveryId().trim())
                    .filter(d -> d.tenantId().equals(tenantId))
                    .orElseThrow(() -> new IllegalArgumentException("Delivery not found or unauthorized"));

            dispatcher.queueReplay(delivery.id());
            replayedEventIds.add(delivery.eventId());
            return ResponseEntity.ok(new ReplayResponse(1, replayedEventIds, "Single delivery replay queued"));
        }

        // Case 2: Replay by specific Event ID
        if (request != null && request.eventId() != null && !request.eventId().isBlank()) {
            Event event = eventRepository.findById(request.eventId().trim())
                    .filter(e -> e.tenantId().equals(tenantId))
                    .orElseThrow(() -> new IllegalArgumentException("Event not found or unauthorized"));

            boolean queued = false;
            for (WebhookDelivery delivery : deliveryRepository.findByEventId(event.id())) {
                if (delivery.status() == com.relayhub.model.DeliveryStatus.DEAD_LETTERED
                        || delivery.status() == com.relayhub.model.DeliveryStatus.FAILED) {
                    dispatcher.queueReplay(delivery.id());
                    queued = true;
                }
            }
            if (queued) replayedEventIds.add(event.id());
            return ResponseEntity.ok(new ReplayResponse(replayedEventIds.size(), replayedEventIds,
                    queued ? "Event replay queued" : "No eligible deliveries to replay"));
        }

        if (request == null || !Boolean.TRUE.equals(request.replayAllDeadLetters())) {
            throw new IllegalArgumentException("Specify a deliveryId, eventId, or replayAllDeadLetters=true");
        }
        // Case 3: Bulk replay all dead-lettered events for tenant
        List<WebhookDelivery> deadLetters = deliveryRepository.findDeadLettersByTenantId(tenantId);
        for (WebhookDelivery dl : deadLetters) {
            try {
                dispatcher.queueReplay(dl.id());
                if (!replayedEventIds.contains(dl.eventId())) {
                    replayedEventIds.add(dl.eventId());
                }
            } catch (Exception e) {
                log.error("Failed to replay dead-letter delivery {}: {}", dl.id(), e.getMessage());
            }
        }

        return ResponseEntity.ok(new ReplayResponse(
                replayedEventIds.size(),
                replayedEventIds,
                "Bulk replay processed for " + replayedEventIds.size() + " events"
        ));
    }
}

