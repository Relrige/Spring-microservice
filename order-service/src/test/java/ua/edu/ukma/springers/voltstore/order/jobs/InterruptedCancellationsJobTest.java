package ua.edu.ukma.springers.voltstore.order.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.config.CancellationProperties;
import ua.edu.ukma.springers.voltstore.order.entities.DeliveryDetails;
import ua.edu.ukma.springers.voltstore.order.entities.OrderEntity;
import ua.edu.ukma.springers.voltstore.order.entities.OrderItemEntity;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.services.CancellationSaga;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The job's choice of orders (back-off), its claim, and its isolation of failures. The saga itself is mocked. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InterruptedCancellationsJobTest {
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final CancellationProperties PROPERTIES = new CancellationProperties(Duration.ofMinutes(1), 100,
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(30)), 5);

    @Mock
    private OrderRepository orders;

    @Mock
    private CancellationSaga saga;

    private final InterruptedCancellationsJob job = new InterruptedCancellationsJob(null, null,
            TransactionOperations.withoutTransaction(), PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void theBackoff_is1_5_15_thenEvery30Minutes() {
        assertThat(PROPERTIES.backoffAfter(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(PROPERTIES.backoffAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(PROPERTIES.backoffAfter(2)).isEqualTo(Duration.ofMinutes(5));
        assertThat(PROPERTIES.backoffAfter(3)).isEqualTo(Duration.ofMinutes(15));
        assertThat(PROPERTIES.backoffAfter(4)).isEqualTo(Duration.ofMinutes(30));
        assertThat(PROPERTIES.backoffAfter(12)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void anOrderIsDue_onlyAfterItsBackoff() {
        // A fresh request: its own first run gets a minute before the job steps in
        assertThat(job.isDue(cancelling(NOW.minusSeconds(59), 0), NOW)).isFalse();
        assertThat(job.isDue(cancelling(NOW.minusSeconds(60), 0), NOW)).isTrue();
        // After the second failure: 5 minutes
        assertThat(job.isDue(cancelling(NOW.minus(Duration.ofMinutes(4)), 2), NOW)).isFalse();
        assertThat(job.isDue(cancelling(NOW.minus(Duration.ofMinutes(5)), 2), NOW)).isTrue();
        // Flagged orders keep being retried every 30 minutes
        assertThat(job.isDue(cancelling(NOW.minus(Duration.ofMinutes(29)), 7), NOW)).isFalse();
        assertThat(job.isDue(cancelling(NOW.minus(Duration.ofMinutes(30)), 7), NOW)).isTrue();
    }

    @Test
    void dueOrdersAreClaimed_andRun_othersAreSkipped() {
        InterruptedCancellationsJob job = jobWithMocks();
        OrderEntity due = cancelling(NOW.minus(Duration.ofMinutes(2)), 0);
        OrderEntity notDue = cancelling(NOW.minusSeconds(10), 0);
        candidates(due, notDue);

        job.retryDueCancellations();

        verify(saga).run(due.getId());
        verify(saga, never()).run(notDue.getId());
        assertThat(due.getCancellationLastAttemptAt()).as("the claim restarts the back-off").isEqualTo(NOW);
        verify(orders).saveAndFlush(due);
    }

    @Test
    void anOrderClaimedByAnotherReplica_isSkipped() {
        InterruptedCancellationsJob job = jobWithMocks();
        OrderEntity due = cancelling(NOW.minus(Duration.ofMinutes(2)), 0);
        candidates(due);
        when(orders.saveAndFlush(due))
                .thenThrow(new ObjectOptimisticLockingFailureException(OrderEntity.class, due.getId()));

        job.retryDueCancellations();

        verify(saga, never()).run(any());
    }

    @Test
    void oneFailingOrder_doesNotStopTheRun() {
        InterruptedCancellationsJob job = jobWithMocks();
        OrderEntity broken = cancelling(NOW.minus(Duration.ofMinutes(3)), 0);
        OrderEntity next = cancelling(NOW.minus(Duration.ofMinutes(2)), 0);
        candidates(broken, next);
        when(saga.run(broken.getId())).thenThrow(new IllegalStateException("bug"));

        job.retryDueCancellations();

        verify(saga).run(next.getId());
    }

    private InterruptedCancellationsJob jobWithMocks() {
        return new InterruptedCancellationsJob(orders, saga, TransactionOperations.withoutTransaction(), PROPERTIES,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void candidates(OrderEntity... candidates) {
        when(orders.findInterruptedCancellations(any())).thenReturn(List.of(candidates));
        for (OrderEntity candidate : candidates) {
            when(orders.findById(candidate.getId())).thenReturn(Optional.of(candidate));
        }
    }

    private static OrderEntity cancelling(Instant lastAttempt, int failures) {
        OrderEntity order = OrderEntity.place(UUID.randomUUID(), UUID.randomUUID(), "ivan@example.com",
                new DeliveryDetails("Ivan Petrenko", "+380501234567", "Kyiv, branch 12", "NOVA_POSHTA"),
                List.of(OrderItemEntity.snapshot(UUID.randomUUID(), "Phone", new BigDecimal("100.00"), 1)),
                "UAH", NOW.minus(Duration.ofHours(1)), Duration.ofMinutes(15));
        order.requestCancellation(lastAttempt);
        for (int i = 0; i < failures; i++) {
            order.recordCancellationFailure("refund: refund_rejected", lastAttempt, 5);
        }
        return order;
    }
}
