package com.relayhub.security;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProductionSafetyChecksTest {
    MockEnvironment safe() { return new MockEnvironment().withProperty("spring.datasource.password","unique-private-password")
            .withProperty("relayhub.webhook.allowed-hosts","receiver.example.com").withProperty("relayhub.webhook.require-https","true"); }
    @Test void acceptsExplicitSafeConfiguration() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(),any())).thenReturn(0L);
        assertDoesNotThrow(()->new ProductionSafetyChecks(safe(),jdbc).afterSingletonsInstantiated());
    }
    @Test void rejectsPublishedDemoCredentialsAndUnsafeDefaults() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(),any())).thenReturn(1L);
        assertThrows(IllegalStateException.class,()->new ProductionSafetyChecks(safe(),jdbc).afterSingletonsInstantiated());
        for (var env:new MockEnvironment[]{safe().withProperty("spring.datasource.password","relayhub_secret"),
                safe().withProperty("relayhub.webhook.allowed-hosts","*"),safe().withProperty("relayhub.webhook.require-https","false")}) {
            assertThrows(IllegalStateException.class,()->new ProductionSafetyChecks(env,jdbc).afterSingletonsInstantiated());
        }
    }
}
