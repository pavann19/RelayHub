package com.relayhub.security;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Refuse known unsafe deployment defaults before application lifecycle startup. */
@Component
@Profile("production")
@DependsOnDatabaseInitialization
public class ProductionSafetyChecks implements SmartInitializingSingleton {
    private final Environment environment;
    private final JdbcTemplate jdbc;
    public ProductionSafetyChecks(Environment environment, JdbcTemplate jdbc) { this.environment=environment; this.jdbc=jdbc; }
    @Override public void afterSingletonsInstantiated() {
        String password=environment.getProperty("spring.datasource.password", "");
        if (password.length()<16 || password.equals("relayhub_secret")) fail("Provide a non-demo database password of at least 16 characters");
        String hosts=environment.getProperty("relayhub.webhook.allowed-hosts", "");
        if (hosts.isBlank() || hosts.contains("*")) fail("Configure explicit webhook allowed hosts");
        if (!environment.getProperty("relayhub.webhook.require-https", Boolean.class, false)) fail("Production webhook HTTPS is required");
        Long demo=jdbc.queryForObject("""
                SELECT count(*) FROM api_keys WHERE is_active=true AND key_hash IN (?,?)
                """, Long.class,
                ApiKeyHasher.hashKey("rlh_live_demo1234567890abcdef"),
                ApiKeyHasher.hashKey("rlh_admin_9876543210fedcba"));
        if (demo!=null && demo>0) fail("Revoke the published demo API keys before enabling production");
    }
    private static void fail(String message) { throw new IllegalStateException("Production safety check: " + message); }
}
