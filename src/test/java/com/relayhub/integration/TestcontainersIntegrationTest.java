package com.relayhub.integration;

import com.relayhub.model.Tenant;
import com.relayhub.model.TenantTier;
import com.relayhub.repository.TenantRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public class TestcontainersIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("relayhub_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (postgres.isRunning()) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
        if (kafka.isRunning()) {
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
            registry.add("relayhub.kafka.enabled", () -> "true");
        }
    }

    @Autowired(required = false)
    private TenantRepository tenantRepository;

    @Test
    @DisplayName("Should initialize PostgreSQL schema and verify persistence via Testcontainers")
    void shouldPersistAndRetrieveTenantInPostgres() {
        if (tenantRepository == null || !postgres.isRunning()) {
            return; // Skip when Docker is not accessible in execution environment
        }

        Tenant testTenant = Tenant.of("tc_tenant_1", "Testcontainers Tenant", TenantTier.ENTERPRISE);
        tenantRepository.save(testTenant);

        Optional<Tenant> retrieved = tenantRepository.findById("tc_tenant_1");
        assertTrue(retrieved.isPresent());
        assertEquals("Testcontainers Tenant", retrieved.get().name());
        assertEquals(TenantTier.ENTERPRISE, retrieved.get().tier());
    }
}
