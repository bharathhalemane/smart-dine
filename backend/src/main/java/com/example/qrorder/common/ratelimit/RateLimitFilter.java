package com.example.qrorder.common.ratelimit;

import com.example.qrorder.common.error.CorrelationIdFilter;
import com.example.qrorder.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns away clients that send too much. Runs right after the trace-id filter and before Spring Security, so a
 * flood of bad logins is refused before any password is checked. Switch off with app.rate-limit.enabled=false.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "app.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final ObjectMapper mapper;
    private final RateLimiter limiter;
    private final List<RateRule> rules;

    @Autowired
    public RateLimitFilter(ObjectMapper mapper, Environment env) {
        this(mapper, RateLimitSettings.from(env), System::nanoTime);
    }

    public RateLimitFilter(ObjectMapper mapper, RateLimitSettings settings, LongSupplier nanos) {
        this.mapper = mapper;
        this.limiter = new RateLimiter(nanos);
        this.rules = RateRules.build(settings);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        for (RateRule rule : rules) {
            if (!rule.applies().test(request)) {
                continue;
            }
            RateLimiter.Decision decision = limiter.tryAcquire(rule.name() + "|" + rule.key().apply(request),
                    rule.limit(), rule.window());
            if (!decision.allowed()) {
                log.debug("Rate limit '{}' hit by {} on {} {}", rule.name(), request.getRemoteAddr(),
                        request.getMethod(), request.getRequestURI());
                reject(response, decision.retryAfterSeconds());
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        ErrorCode code = ErrorCode.RATE_LIMITED;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(),
                "Too many requests. Try again in " + retryAfterSeconds + " seconds");
        problem.setTitle(code.status().getReasonPhrase());
        problem.setProperty("code", code.name());
        problem.setProperty("traceId", MDC.get(CorrelationIdFilter.MDC_KEY));
        response.setStatus(code.status().value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}