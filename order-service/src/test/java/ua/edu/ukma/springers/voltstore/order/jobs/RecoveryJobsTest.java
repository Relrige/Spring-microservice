package ua.edu.ukma.springers.voltstore.order.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.support.TransactionOperations;
import ua.edu.ukma.springers.voltstore.order.config.CancellationProperties;
import ua.edu.ukma.springers.voltstore.order.config.RecoveryProperties;
import ua.edu.ukma.springers.voltstore.order.entities.PendingRefund;
import ua.edu.ukma.springers.voltstore.order.entities.ReservationAttempt;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations;
import ua.edu.ukma.springers.voltstore.order.repositories.OrderRepository;
import ua.edu.ukma.springers.voltstore.order.repositories.PendingRefundRepository;
import ua.edu.ukma.springers.voltstore.order.repositories.ReservationAttemptRepository;
import ua.edu.ukma.springers.voltstore.order.services.OwedRefunds;

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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link OrphanReservationJob} and {@link PendingRefundJob} with their collaborators mocked. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecoveryJobsTest {
    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final RecoveryProperties RECOVERY = new RecoveryProperties(Duration.ofMinutes(1),
            Duration.ofMinutes(2), 100);
    private static final CancellationProperties BACKOFF = new CancellationProperties(Duration.ofMinutes(1), 100,
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(30)), 5);

    @Mock
    private ReservationAttemptRepository attempts;

    @Mock
    private OrderRepository orders;

    @Mock
    private StockReservations stock;

    @Mock
    private PendingRefundRepository pendingRefunds;

    @Mock
    private OwedRefunds owedRefunds;

    // ---- OrphanReservationJob ----

    @Test
    void orphans_lookOnlyAtAttemptsOlderThanTheSettleTime() {
        orphanJob().releaseOrphanReservations();

        verify(attempts).findByCreatedAtLessThanEqualOrderByCreatedAtAsc(NOW.minus(Duration.ofMinutes(2)),
                Limit.of(100));
    }

    @Test
    void anAttemptWithoutAnOrder_isReleased_thenDeleted() {
        UUID orderId = givenAttempt();

        orphanJob().releaseOrphanReservations();

        verify(stock).release(orderId);
        verify(attempts).deleteById(orderId);
    }

    @Test
    void anAttemptWhoseOrderExists_isOnlyDeleted() {
        UUID orderId = givenAttempt();
        when(orders.existsById(orderId)).thenReturn(true);

        orphanJob().releaseOrphanReservations();

        verify(stock, never()).release(any());
        verify(attempts).deleteById(orderId);
    }

    @Test
    void aFailedRelease_keepsTheAttempt_andCountsTheFailure() {
        UUID orderId = givenAttempt();
        ReservationAttempt attempt = attempts.findById(orderId).orElseThrow();
        doThrow(new DependencyUnavailableException("Inventory", "down", null)).when(stock).release(orderId);

        orphanJob().releaseOrphanReservations();

        verify(attempts, never()).deleteById(any());
        assertThat(attempt.getReleaseAttempts()).isEqualTo(1);
        assertThat(attempt.getLastError()).contains("Inventory");
    }

    @Test
    void oneFailingAttempt_doesNotStopTheRun() {
        UUID broken = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(attempts.findByCreatedAtLessThanEqualOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(
                new ReservationAttempt(broken, NOW.minusSeconds(600)), new ReservationAttempt(next,
                        NOW.minusSeconds(300))));
        when(orders.existsById(broken)).thenThrow(new IllegalStateException("bug"));

        orphanJob().releaseOrphanReservations();

        verify(stock).release(next);
    }

    // ---- PendingRefundJob ----

    @Test
    void refunds_areRetriedOnlyAfterTheirBackoff() {
        PendingRefund due = owed(NOW.minus(Duration.ofMinutes(5)), 2);
        PendingRefund notDue = owed(NOW.minus(Duration.ofMinutes(4)), 2);
        PendingRefund flaggedAndDue = owed(NOW.minus(Duration.ofMinutes(30)), 7);
        when(pendingRefunds.findAllByOrderByLastAttemptAtAsc(any())).thenReturn(List.of(due, notDue, flaggedAndDue));

        refundJob().retryDueRefunds();

        verify(owedRefunds).attempt(due.getOrderId());
        verify(owedRefunds, never()).attempt(notDue.getOrderId());
        verify(owedRefunds).attempt(flaggedAndDue.getOrderId());
    }

    @Test
    void oneFailingRefund_doesNotStopTheRun() {
        PendingRefund broken = owed(NOW.minus(Duration.ofHours(1)), 0);
        PendingRefund next = owed(NOW.minus(Duration.ofHours(1)), 0);
        when(pendingRefunds.findAllByOrderByLastAttemptAtAsc(any())).thenReturn(List.of(broken, next));
        doThrow(new IllegalStateException("bug")).when(owedRefunds).attempt(broken.getOrderId());

        refundJob().retryDueRefunds();

        verify(owedRefunds).attempt(next.getOrderId());
    }

    private UUID givenAttempt() {
        UUID orderId = UUID.randomUUID();
        ReservationAttempt attempt = new ReservationAttempt(orderId, NOW.minus(Duration.ofMinutes(5)));
        when(attempts.findByCreatedAtLessThanEqualOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(attempt));
        when(attempts.findById(orderId)).thenReturn(Optional.of(attempt));
        return orderId;
    }

    private static PendingRefund owed(Instant lastAttempt, int failures) {
        PendingRefund owed = new PendingRefund(UUID.randomUUID(), new BigDecimal("200.00"), "UAH", lastAttempt);
        for (int i = 0; i < failures; i++) {
            owed.recordFailure("refund: timeout", lastAttempt, 5);
        }
        return owed;
    }

    private OrphanReservationJob orphanJob() {
        return new OrphanReservationJob(attempts, orders, stock, TransactionOperations.withoutTransaction(), RECOVERY,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PendingRefundJob refundJob() {
        return new PendingRefundJob(pendingRefunds, owedRefunds, RECOVERY, BACKOFF, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
