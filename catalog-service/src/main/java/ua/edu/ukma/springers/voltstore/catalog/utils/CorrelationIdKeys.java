package ua.edu.ukma.springers.voltstore.catalog.utils;

// Same names as in order-service, so one ID follows a request across services
public final class CorrelationIdKeys {
    public static final String CORRELATION_ID_HTTP_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private CorrelationIdKeys() {
    }
}
