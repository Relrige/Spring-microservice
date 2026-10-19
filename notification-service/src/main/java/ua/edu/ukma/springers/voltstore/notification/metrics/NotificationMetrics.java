package ua.edu.ukma.springers.voltstore.notification.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationStatus;
import ua.edu.ukma.springers.voltstore.notification.entities.NotificationType;
import ua.edu.ukma.springers.voltstore.notification.repositories.NotificationRepository;

/**
 * The service's own meters (spec, <i>Metrics</i>; scraped at {@code /actuator/prometheus}):
 * <ul>
 *   <li>Counter {@code voltstore_notifications_total{type, outcome}}: one per sending attempt of the sender job,
 *   {@code outcome} = {@code sent}, {@code retry} (failed, tried again later) or {@code failed} (given up).</li>
 *   <li>Gauge {@code voltstore_notifications_pending}: the number of {@code PENDING} rows, read from the database
 *   when Prometheus scrapes (like Catalog's {@code voltstore_outbox_pending_events}), so it needs no bookkeeping.
 *   A value that keeps growing is the first sign of an SMTP outage, before customers notice missing emails. If the
 *   database is down, the read fails and Micrometer reports NaN instead of failing the scrape.</li>
 * </ul>
 * Label values are bounded: both are enums. Never an order ID or a failure message.
 */
@Component
public class NotificationMetrics {
    private static final String COUNTER = "voltstore.notifications";

    private final MeterRegistry registry;

    public NotificationMetrics(NotificationRepository repository, MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("voltstore.notifications.pending", repository,
                        notifications -> notifications.countByStatus(NotificationStatus.PENDING))
                .description("Notifications waiting to be sent")
                .register(registry);
    }

    public void record(NotificationType type, Outcome outcome) {
        Counter.builder(COUNTER)
                .description("Sending attempts of the notification sender job, by email type and outcome")
                .tag("type", type.name())
                .tag("outcome", outcome.label)
                .register(registry)
                .increment();
    }

    public enum Outcome {
        SENT("sent"),
        RETRY("retry"),
        FAILED("failed");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }
    }
}
