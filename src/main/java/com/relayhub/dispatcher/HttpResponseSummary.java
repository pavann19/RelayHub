package com.relayhub.dispatcher;

import java.util.Map;

public record HttpResponseSummary(
        int statusCode,
        Map<String, String> headers,
        String body,
        long latencyMs,
        String errorMessage
) {
    public static HttpResponseSummary success(int statusCode, Map<String, String> headers, String body, long latencyMs) {
        return new HttpResponseSummary(statusCode, headers, body, latencyMs, null);
    }

    public static HttpResponseSummary error(int statusCode, String error, long latencyMs) {
        return new HttpResponseSummary(statusCode, Map.of(), null, latencyMs, error);
    }
}

