package com.relayhub.security;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebhookTargetPolicyTest {
    @Test
    void rejectsPrivateReservedAndUnlistedDestinations() throws Exception {
        for (String host : new String[]{"127.0.0.1", "10.0.0.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "192.168.1.1", "::1", "fc00::1", "2001:db8::1"}) {
            assertFalse(WebhookTargetPolicy.isPublic(InetAddress.getByName(host)), host);
        }
        assertTrue(WebhookTargetPolicy.isPublic(InetAddress.getByName("8.8.8.8")));
        assertThrows(IllegalArgumentException.class, () -> new WebhookTargetPolicy("", false).validate("https://8.8.8.8/hook"));
        assertThrows(IllegalArgumentException.class, () -> new WebhookTargetPolicy("127.0.0.1", false).validate("http://127.0.0.1/hook"));
    }

    @Test
    void requiresExplicitPrivateOptInAndRejectsUrlAmbiguity() {
        var policy = new WebhookTargetPolicy("127.0.0.1", true);
        assertDoesNotThrow(() -> policy.validate("http://127.0.0.1:8080/hook"));
        for (String url : new String[]{"file:///etc/passwd", "http://user@127.0.0.1/hook", "http://127.0.0.1/hook#fragment", "http://127.0.0.1.evil.test/hook", "http://127.0.0.1:99999/hook"}) {
            assertThrows(IllegalArgumentException.class, () -> policy.validate(url), url);
        }
    }
}
