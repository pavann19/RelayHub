package com.relayhub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayhub.metrics.RelayHubMetrics;
import com.relayhub.model.ApiKey;
import com.relayhub.model.Tenant;
import com.relayhub.repository.ApiKeyRepository;
import com.relayhub.repository.TenantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyRepository apiKeyRepository;
    private final TenantRepository tenantRepository;
    private final RelayHubMetrics metrics;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthenticationFilter(
            ApiKeyRepository apiKeyRepository,
            TenantRepository tenantRepository,
            RelayHubMetrics metrics,
            ObjectMapper objectMapper
    ) {
        this.apiKeyRepository = apiKeyRepository;
        this.tenantRepository = tenantRepository;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Skip auth for public health/metrics/docs endpoints
        if (isPublicPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawKey = extractApiKey(request);

        if (rawKey != null && !rawKey.isBlank()) {
            String keyHash = ApiKeyHasher.hashKey(rawKey.trim());
            Optional<ApiKey> apiKeyOpt = apiKeyRepository.findByKeyHash(keyHash);

            if (apiKeyOpt.isPresent()) {
                ApiKey apiKey = apiKeyOpt.get();
                if (!apiKey.isValid()) {
                    metrics.recordGatewayRequest(apiKey.tenantId(), request.getMethod(), 401);
                    sendProblemDetail(response, HttpStatus.UNAUTHORIZED, "API key is revoked or expired", path);
                    return;
                }

                Optional<Tenant> tenantOpt = tenantRepository.findById(apiKey.tenantId());
                if (tenantOpt.isEmpty()) {
                    metrics.recordGatewayRequest(apiKey.tenantId(), request.getMethod(), 401);
                    sendProblemDetail(response, HttpStatus.UNAUTHORIZED, "Associated tenant not found", path);
                    return;
                }

                Tenant tenant = tenantOpt.get();
                TenantContext.setTenant(tenant);
                ApiKeyAuthenticationToken auth = new ApiKeyAuthenticationToken(tenant, apiKey.role());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else {
                metrics.recordGatewayRequest("unknown", request.getMethod(), 401);
                sendProblemDetail(response, HttpStatus.UNAUTHORIZED, "Invalid API key provided", path);
                return;
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private String extractApiKey(HttpServletRequest request) {
        String header = request.getHeader("X-API-Key");
        if (header != null && !header.isBlank()) {
            return header;
        }
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer rlh_")) {
            return authHeader.substring(7);
        }
        return null;
    }

    private boolean isPublicPath(String path) {
        return (path.equals("/actuator/health") || path.startsWith("/actuator/health/")) ||
               path.startsWith("/swagger-ui") ||
               path.startsWith("/v3/api-docs") ||
               path.equals("/") ||
               path.equals("/error");
    }

    private void sendProblemDetail(HttpServletResponse response, HttpStatus status, String detail, String path) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(path));
        problem.setTitle(status.getReasonPhrase());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}

