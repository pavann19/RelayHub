package com.relayhub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record ReplayRequest(
        @Schema(description = "Specific Event ID to replay", example = "evt_01948b8e-d901-729c-99f5-7489a3cf1201")
        String eventId,

        @Schema(description = "Specific Delivery ID to replay", example = "del_01948b8e-d901-729c-99f5-7489a3cf1204")
        String deliveryId,

        @Schema(description = "Target Subscription ID (optional filter)", example = "sub_01948b8e-d901-729c-99f5-7489a3cf1202")
        String subscriptionId,

        @Schema(description = "Whether to replay all dead-lettered events for the tenant", example = "false")
        Boolean replayAllDeadLetters
) {}

