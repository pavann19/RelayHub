package com.relayhub.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayhub.dto.RateLimitResult;
import com.relayhub.metrics.RelayHubMetrics;
import com.relayhub.model.Tenant;
import com.relayhub.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final RelayHubMetrics metrics;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiter rateLimiter, RelayHubMetrics metrics, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
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

        // Apply rate limits only to API endpoints (/api/**)
        if (!path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        Tenant tenant = TenantContext.getTenant();
        if (tenant == null) {
            // Unauthenticated requests are handled by Spring Security / ApiKey filter
            filterChain.doFilter(request, response);
            return;
        }

        RateLimitResult result = rateLimiter.tryConsume(tenant, 1);

        // Always attach standard rate limiting headers
        response.setHeader("X-RateLimit-Limit", String.valueOf(result.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));
        response.setHeader("X-RateLimit-Reset", String.valueOf(result.resetSeconds()));

        if (!result.allowed()) {
            response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
            metrics.recordRateLimitExceeded(tenant.id());
            metrics.recordGatewayRequest(tenant.id(), request.getMethod(), 429);

            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Tenant rate limit or monthly quota exceeded. Please retry after " + result.retryAfterSeconds() + " seconds."
            );
            problem.setTitle("Too Many Requests");
            problem.setInstance(URI.create(path));
            objectMapper.writeValue(response.getOutputStream(), problem);
            return;
        }

        filterChain.doFilter(request, response);
    }
}

