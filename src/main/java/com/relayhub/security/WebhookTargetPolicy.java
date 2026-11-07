package com.relayhub.security;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Operator-owned exact-host allowlist; private networks require a separate opt-in. */
@Component
public class WebhookTargetPolicy {
    private final Set<String> allowedHosts;
    private final boolean allowPrivate;

    public WebhookTargetPolicy(@Value("${relayhub.webhook.allowed-hosts:}") String hosts,
            @Value("${relayhub.webhook.allow-private-targets:false}") boolean allowPrivate) {
        this.allowedHosts = Arrays.stream(hosts.split(",")).map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        this.allowPrivate = allowPrivate;
    }

    public void validate(String target) {
        try {
            URI uri = URI.create(target);
            String host = uri.getHost();
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || host == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Webhook target must use an explicitly allowed host and HTTP(S) URL");
            }
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (!allowPrivate && !isPublic(address)) {
                    throw new IllegalArgumentException("Private or reserved webhook destinations are disabled");
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("Webhook hostname could not be resolved", e);
        }
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = b[0] & 255, c = b[1] & 255;
            return a != 0 && a != 10 && a != 127 && a < 224
                    && !(a == 100 && c >= 64 && c <= 127)
                    && !(a == 169 && c == 254) && !(a == 172 && c >= 16 && c <= 31)
                    && !(a == 192 && (c == 168 || c == 0 || c == 2))
                    && !(a == 198 && (c == 18 || c == 19 || c == 51)) && !(a == 203 && c == 0);
        }
        // Only global unicast IPv6; exclude documentation and transition ranges.
        return (b[0] & 0xe0) == 0x20 && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x02)
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x01 && (b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8)
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x01 && b[2] == 0 && b[3] == 0);
    }
}
