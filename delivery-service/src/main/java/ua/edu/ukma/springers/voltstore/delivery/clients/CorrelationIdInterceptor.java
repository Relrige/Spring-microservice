package ua.edu.ukma.springers.voltstore.delivery.clients;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

import static ua.edu.ukma.springers.voltstore.delivery.filters.CorrelationIdKeys.CORRELATION_ID_HTTP_HEADER;
import static ua.edu.ukma.springers.voltstore.delivery.filters.CorrelationIdKeys.CORRELATION_ID_MDC_KEY;

// Forwards the current request's correlation ID (set by CorrelationIdFilter), as the order-service client does
class CorrelationIdInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
        if (correlationId != null) {
            request.getHeaders().set(CORRELATION_ID_HTTP_HEADER, correlationId);
        }
        return execution.execute(request, body);
    }
}
