package ua.edu.ukma.springers.voltstore.order.outbox;

import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Carries the request's trace across the outbox (observability task 03). {@link Outbox} stores the W3C
 * {@code traceparent} of the request that wrote the event; {@link OutboxRelay} makes it current again while it sends,
 * so the KafkaTemplate's producer span is a child of the request's span and its {@code traceparent} header continues
 * that trace. Without tracing (slim test contexts) nothing is stored and nothing is restored.
 */
@Component
public class OutboxTraceContext {
    private static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public OutboxTraceContext(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
        this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
    }

    // The current span's traceparent, or null outside a trace (scheduled jobs)
    String current() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /**
     * Makes the stored context current until the scope is closed; a no-op for {@code null} (an event written outside
     * a trace, which the producer span then starts).
     */
    CurrentTraceContext.Scope restore(String traceparent) {
        // 00-<32 hex trace id>-<16 hex span id>-<2 hex flags>, as written by current()
        String[] parts = traceparent == null ? new String[0] : traceparent.split("-");
        if (parts.length != 4) {
            return CurrentTraceContext.Scope.NOOP;
        }
        TraceContext context = tracer.traceContextBuilder()
                .traceId(parts[1])
                .spanId(parts[2])
                .sampled((Integer.parseInt(parts[3], 16) & 1) == 1)
                .build();
        return tracer.currentTraceContext().newScope(context);
    }
}
