package com.relayhub.dispatcher;

import com.relayhub.security.WebhookTargetPolicy;
import java.net.InetAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/** Each attempt connects only to the exact addresses approved by the target policy. */
@Component
public class HttpWebhookClient implements AutoCloseable {
    private final javax.net.ssl.SSLContext tlsContext;
    public HttpWebhookClient() { this(null); }
    // Package-visible constructor permits isolated trust-anchor tests without global JVM changes.
    HttpWebhookClient(javax.net.ssl.SSLContext tlsContext) { this.tlsContext = tlsContext; }

    private WebhookTargetPolicy targetPolicy = new WebhookTargetPolicy("", false);
    private final java.util.concurrent.ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "webhook-deadline"); thread.setDaemon(true); return thread;
    });

    @org.springframework.beans.factory.annotation.Autowired
    public void setTargetPolicy(WebhookTargetPolicy policy) { this.targetPolicy = policy; }

    public HttpResponseSummary sendWebhook(String targetUrl, String payload, String signatureHeader,
            String eventType, String deliveryId, int timeoutMs) {
        long start = System.nanoTime();
        try {
            URI target = URI.create(targetUrl);
            InetAddress[] approved = targetPolicy.validateAndResolve(targetUrl);
            DnsResolver pinned = new DnsResolver() {
                public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                    if (!host.equalsIgnoreCase(target.getHost())) throw new java.net.UnknownHostException("Unexpected host");
                    return approved.clone();
                }
                public String resolveCanonicalHostname(String host) { return target.getHost(); }
            };
            Timeout timeout = Timeout.ofMilliseconds(Math.max(500, timeoutMs));
            var managerBuilder = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(pinned)
                    .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build());
            if (tlsContext != null) managerBuilder.setTlsSocketStrategy(
                    org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder.create().setSslContext(tlsContext).buildClassic());
            var manager = managerBuilder.build();
            // Retain hostname in the URI: TLS certificate/hostname verification and SNI remain enabled.
            try (var client = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling()
                    .disableAutomaticRetries().disableCookieManagement().build()) {
                HttpPost request = new HttpPost(target);
                request.setConfig(RequestConfig.custom().setResponseTimeout(timeout).setConnectionRequestTimeout(timeout).build());
                request.setHeader("User-Agent", "RelayHub-Webhook-Delivery/1.0");
                request.setHeader("X-RelayHub-Signature", signatureHeader);
                request.setHeader("X-RelayHub-Event", eventType);
                request.setHeader("X-RelayHub-Delivery", deliveryId);
                request.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON));
                var deadline = deadlines.schedule(request::cancel, Math.max(500, timeoutMs), TimeUnit.MILLISECONDS);
                try {
                    return client.execute(request, response -> {
                        var headers = new HashMap<String, String>();
                        for (var header : response.getHeaders()) headers.merge(header.getName().toLowerCase(java.util.Locale.ROOT), header.getValue(), (a,b) -> a + "," + b);
                        String preview = "";
                        if (response.getEntity() != null) {
                            try (var body = response.getEntity().getContent()) {
                                byte[] bytes = body.readNBytes(1024);
                                preview = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).replace('\0', '\uFFFD');
                                if (bytes.length == 1024) preview += "... [preview limit]";
                            }
                        }
                        return HttpResponseSummary.success(response.getCode(), headers, preview, (System.nanoTime() - start) / 1_000_000);
                    });
                } finally { deadline.cancel(false); }
            }
        } catch (Exception error) {
            return HttpResponseSummary.error(0, error.getClass().getSimpleName(), (System.nanoTime() - start) / 1_000_000);
        }
    }

    @Override @jakarta.annotation.PreDestroy
    public void close() { deadlines.shutdownNow(); }
}
