package com.relayhub.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI relayHubOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("RelayHub — Reliable API Gateway & Webhook Delivery Platform")
                        .description("High-performance infrastructure service that ingests API events, enforces tenant rate limits & quotas, guarantees idempotency, and delivers webhooks with exponential backoff and HMAC-SHA256 signatures.")
                        .version("1.0.0")
                        .contact(new Contact().name("RelayHub Core Platform Team").email("platform@relayhub.io"))
                        .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
                .addSecurityItem(new SecurityRequirement().addList("ApiKeyAuth").addList("BearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("ApiKeyAuth", new SecurityScheme()
                                .name("X-API-Key")
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .description("Tenant API Key (e.g. rlh_live_...)"))
                        .addSecuritySchemes("BearerAuth", new SecurityScheme()
                                .name("Authorization")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("OAuth2 / OIDC JWT Bearer Token")));
    }
}

