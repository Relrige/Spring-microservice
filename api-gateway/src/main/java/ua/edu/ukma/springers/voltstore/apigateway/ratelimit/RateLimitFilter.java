package ua.edu.ukma.springers.voltstore.apigateway.ratelimit;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import ua.edu.ukma.springers.voltstore.apigateway.errors.GatewayProblems;

import java.time.Duration;

/**
 * The first step of the request pipeline: counts each request against a limit for its client IP address and answers
 * {@code 429} when it is exceeded, before the JWT is checked and without calling the upstream. Login and registration
 * have a strict limit, every other route a general one ({@link RateLimitProperties}).
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitFilter {
    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    static final String REJECTED_METRIC = "gateway.rate.limit.rejected";

    private final RateLimitProperties properties;
    private final RateLimiterRegistry limiters = RateLimiterRegistry.ofDefaults();
    private final Rule auth;
    private final Rule general;

    public RateLimitFilter(RateLimitProperties properties, MeterRegistry meters) {
        this.properties = properties;
        this.auth = new Rule("auth", properties.auth(), meters);
        this.general = new Rule("general", properties.general(), meters);
    }

    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        if (!properties.enabled()) {
            return next.handle(request);
        }
        String routeId = MvcUtils.getAttribute(request, MvcUtils.GATEWAY_ROUTE_ID_ATTR);
        Rule rule = properties.authRoutes().contains(routeId) ? auth : general;
        // Behind the Ingress, Spring Boot resolves the client address from X-Forwarded-For (Kubernetes platform)
        String client = request.servletRequest().getRemoteAddr();
        // shortcut: one limiter per client address, kept in memory for the gateway's lifetime; with several gateway
        // replicas or very many clients, move the counters to a shared store with expiry (Redis)
        RateLimiter limiter = limiters.rateLimiter(rule.name + ":" + client, rule.config);
        if (limiter.acquirePermission()) {
            return next.handle(request);
        }
        rule.rejected.increment();
        log.debug("Rate limit '{}' exceeded on route {}", rule.name, routeId);
        return GatewayProblems.tooManyRequests(rule.retryAfter);
    }

    private static final class Rule {
        private final String name;
        private final RateLimiterConfig config;
        private final Counter rejected;
        // Permissions are refreshed every period, so a client may retry after at most one period
        private final Duration retryAfter;

        private Rule(String name, RateLimitProperties.Limit limit, MeterRegistry meters) {
            this.name = name;
            this.config = RateLimiterConfig.custom()
                    .limitForPeriod(limit.requests())
                    .limitRefreshPeriod(limit.period())
                    .timeoutDuration(Duration.ZERO)
                    .build();
            this.rejected = Counter.builder(REJECTED_METRIC)
                    .description("Requests rejected with 429 by the gateway's rate limiter")
                    .tag("limit", name)
                    .register(meters);
            this.retryAfter = limit.period();
        }
    }
}
