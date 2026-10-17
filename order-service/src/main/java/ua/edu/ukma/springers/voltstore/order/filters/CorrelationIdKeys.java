package ua.edu.ukma.springers.voltstore.order.utils.constants;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class CorrelationIdKeys {
    public static final String CORRELATION_ID_HTTP_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
}
