package com.relayhub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record ReplayResponse(
        @Schema(description = "Total number of events re-enqueued for delivery", example = "1")
        int replayedCount,

        @Schema(description = "List of re-enqueued event IDs")
        List<String> eventIds,

        @Schema(description = "Summary message", example = "Replay queued successfully")
        String message
) {}

