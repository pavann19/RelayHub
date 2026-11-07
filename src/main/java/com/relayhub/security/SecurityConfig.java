package com.relayhub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayhub.metrics.RelayHubMetrics;
import com.relayhub.ratelimit.RateLimitFilter;
import com.relayhub.ratelimit.RateLimiter;
import com.relayhub.repository.ApiKeyRepository;
import com.relayhub.repository.TenantRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ApiKeyRepository apiKeyRepository;
    private final TenantRepository tenantRepository;
    private final RateLimiter rateLimiter;
    private final RelayHubMetrics metrics;
    private final ObjectMapper objectMapper;

    public SecurityConfig(
            ApiKeyRepository apiKeyRepository,
            TenantRepository tenantRepository,
            RateLimiter rateLimiter,
            RelayHubMetrics metrics,
            ObjectMapper objectMapper
    ) {
        this.apiKeyRepository = apiKeyRepository;
        this.tenantRepository = tenantRepository;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        ApiKeyAuthenticationFilter apiKeyFilter = new ApiKeyAuthenticationFilter(
                apiKeyRepository,
                tenantRepository,
                metrics,
                objectMapper
        );

        RateLimitFilter rateLimitFilter = new RateLimitFilter(
                rateLimiter,
                metrics,
                objectMapper
        );

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health", "/actuator/health/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/error",
                                "/"
                        ).permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/api-keys/**", "/api/v1/subscriptions/**", "/api/v1/deliveries/replay/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").hasAnyRole("INGEST", "ADMIN")
                        .anyRequest().permitAll()
                )
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, ApiKeyAuthenticationFilter.class);

        return http.build();
    }
}

