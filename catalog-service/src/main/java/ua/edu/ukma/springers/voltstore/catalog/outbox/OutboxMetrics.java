package ua.edu.ukma.springers.voltstore.catalog.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Gauge {@code voltstore_outbox_pending_events}: how many outbox events are not published yet.
 * <p>
 * A gauge, not a counter, because the value goes up (new products) and down (the relay published them); only the
 * current value matters. It is read when Prometheus scrapes (one {@code count(*)} on the partial index), so it is
 * always current and needs no bookkeeping in the relay. Normally it is 0 or close to it; a value that keeps growing
 * means Kafka is unreachable or an event is stuck (alert {@code OutboxBacklogGrowing}). Both catalog replicas read
 * the same table and report the same number, so alerts use {@code max()}.
 * If the database is down, the read fails and Micrometer reports NaN instead of failing the scrape.
 */
@Component
class OutboxMetrics {

    OutboxMetrics(OutboxRepository repository, MeterRegistry registry) {
        Gauge.builder("voltstore.outbox.pending.events", repository, OutboxRepository::countByPublishedAtIsNull)
                .description("Outbox events waiting to be published to Kafka")
                .register(registry);
    }
}
