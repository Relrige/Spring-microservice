package ua.edu.ukma.springers.voltstore.inventory.filters;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

import static ua.edu.ukma.springers.voltstore.inventory.filters.CorrelationIdKeys.CORRELATION_ID_HTTP_HEADER;
import static ua.edu.ukma.springers.voltstore.inventory.filters.CorrelationIdKeys.CORRELATION_ID_MDC_KEY;

/**
 * Keeps the request's correlation ID in the logging context (MDC), so outgoing calls (see
 * {@code CorrelationIdInterceptor}) carry the same ID. A request without one gets a new ID until the API Gateway
 * sets it (as in order-service and catalog-service).
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_ID_HTTP_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CORRELATION_ID_MDC_KEY);
        }
    }
}
