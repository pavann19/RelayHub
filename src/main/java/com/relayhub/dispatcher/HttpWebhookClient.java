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
public class HttpWebhookClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpWebhookClient.class);
    private static final int MAX_BODY_PREVIEW_LENGTH = 1024;

    private volatile HttpClient httpClient;
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private com.relayhub.security.WebhookTargetPolicy targetPolicy = new com.relayhub.security.WebhookTargetPolicy("", false);

    @org.springframework.beans.factory.annotation.Autowired
    public void setTargetPolicy(com.relayhub.security.WebhookTargetPolicy policy) { this.targetPolicy = policy; }

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
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .connectTimeout(Duration.ofSeconds(5))
                            .executor(executor)
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
            targetPolicy.validate(targetUrl);
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

            HttpResponse<byte[]> response = getOrCreateClient().send(request, info -> new BoundedBodySubscriber(MAX_BODY_PREVIEW_LENGTH, timeoutMs));
            long latencyMs = (System.nanoTime() - startTime) / 1_000_000;

            Map<String, String> responseHeaders = new HashMap<>();
            response.headers().map().forEach((k, v) -> responseHeaders.put(k, String.join(",", v)));

            byte[] bytes = response.body();
            String bodyPreview = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).replace('\0', '\uFFFD');
            if (bytes.length == MAX_BODY_PREVIEW_LENGTH) bodyPreview += "... [preview limit]";

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
    @Override
    @jakarta.annotation.PreDestroy
    public void close() {
        if (httpClient != null) httpClient.close();
        executor.shutdown();
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<byte[]> body = new java.util.concurrent.CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;
        BoundedBodySubscriber(int limit, int timeoutMs) {
            this.limit = limit;
            body.orTimeout(Math.max(500, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .whenComplete((value, error) -> { if (error != null && subscription != null) subscription.cancel(); });
        }
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) { subscription = value; if (body.isDone()) value.cancel(); else value.request(1); }
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                int count = Math.min(buffer.remaining(), limit - bytes.size());
                byte[] chunk = new byte[count];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
                if (bytes.size() == limit) {
                    subscription.cancel();
                    body.complete(bytes.toByteArray());
                    return;
                }
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }

}
