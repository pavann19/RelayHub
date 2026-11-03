package com.relayhub.dispatcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class HttpWebhookClient {

    private static final Logger log = LoggerFactory.getLogger(HttpWebhookClient.class);
    private static final int MAX_BODY_PREVIEW_LENGTH = 1024;

    private volatile HttpClient httpClient;

    public HttpWebhookClient() {
        this(null);
    }

    public HttpWebhookClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    protected HttpClient getOrCreateClient() {
        if (this.httpClient == null) {
            synchronized (this) {
                if (this.httpClient == null) {
                    this.httpClient = HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .connectTimeout(Duration.ofSeconds(5))
                            .executor(Executors.newVirtualThreadPerTaskExecutor())
                            .build();
                }
            }
        }
        return this.httpClient;
    }

    public HttpResponseSummary sendWebhook(
            String targetUrl,
            String payload,
            String signatureHeader,
            String eventType,
            String deliveryId,
            int timeoutMs
    ) {
        long startTime = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofMillis(Math.max(500, timeoutMs)))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "RelayHub-Webhook-Delivery/1.0")
                    .header("X-RelayHub-Signature", signatureHeader)
                    .header("X-RelayHub-Event", eventType)
                    .header("X-RelayHub-Delivery", deliveryId)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = getOrCreateClient().send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = (System.nanoTime() - startTime) / 1_000_000;

            Map<String, String> responseHeaders = new HashMap<>();
            response.headers().map().forEach((k, v) -> responseHeaders.put(k, String.join(",", v)));

            String bodyPreview = response.body();
            if (bodyPreview != null && bodyPreview.length() > MAX_BODY_PREVIEW_LENGTH) {
                bodyPreview = bodyPreview.substring(0, MAX_BODY_PREVIEW_LENGTH) + "... [truncated]";
            }

            return HttpResponseSummary.success(response.statusCode(), responseHeaders, bodyPreview, latencyMs);

        } catch (IOException | InterruptedException e) {
            long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Webhook delivery failed for target {}: {}", targetUrl, e.getMessage());
            return HttpResponseSummary.error(0, e.getMessage(), latencyMs);
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
            log.error("Unexpected error delivering webhook to {}: {}", targetUrl, e.getMessage());
            return HttpResponseSummary.error(0, e.getClass().getSimpleName() + ": " + e.getMessage(), latencyMs);
        }
    }
}
