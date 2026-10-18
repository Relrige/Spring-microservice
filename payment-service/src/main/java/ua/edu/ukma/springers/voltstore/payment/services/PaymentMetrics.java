package ua.edu.ukma.springers.voltstore.payment.services;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.payment.entities.TransactionType;
import ua.edu.ukma.springers.voltstore.payment.gateway.GatewayResult;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Business metrics of Payment Service, exported at {@code /actuator/prometheus} (names below are the Prometheus ones).
 * <ul>
 *   <li><b>Counter</b> {@code voltstore_payments_total{type, outcome}}: every gateway interaction by its outcome
 *   ({@code approved}, {@code declined}, {@code key_mismatch}, {@code timeout}, {@code unavailable}). Only grows; the
 *   alert on the failure rate uses {@code rate()} over it.</li>
 *   <li><b>Timer</b> {@code voltstore_payment_gateway_seconds{type, outcome}}: one gateway interaction including its
 *   retries, i.e. what the customer waits for. Exported with histogram buckets, so p95 can be computed across
 *   instances with {@code histogram_quantile()}.</li>
 *   <li><b>DistributionSummary</b> {@code voltstore_payment_amount{type, currency}}: amounts of approved payments.
 *   Not a duration, so not a Timer; gives count, sum (turnover) and max.</li>
 * </ul>
 * Tags have a small, fixed set of values (never an order ID), so the number of time series stays bounded.
 */
@Component
public class PaymentMetrics {
    static final String PAYMENTS = "voltstore.payments";
    static final String GATEWAY_CALLS = "voltstore.payment.gateway";
    static final String AMOUNT = "voltstore.payment.amount";

    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample startGatewayCall() {
        return Timer.start(registry);
    }

    public void stopGatewayCall(Timer.Sample sample, TransactionType type, String outcome) {
        sample.stop(Timer.builder(GATEWAY_CALLS)
                .description("Duration of one payment gateway interaction, retries included")
                .tag("type", tag(type))
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry));
    }

    public void recordPayment(TransactionType type, String outcome) {
        Counter.builder(PAYMENTS)
                .description("Finished charges and refunds by outcome")
                .tag("type", tag(type))
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    public void recordApprovedAmount(TransactionType type, BigDecimal amount, String currency) {
        DistributionSummary.builder(AMOUNT)
                .description("Amounts of approved charges and refunds")
                .tag("type", tag(type))
                .tag("currency", currency)
                .register(registry)
                .record(amount.doubleValue());
    }

    /** Outcome tag of a definitive gateway answer. */
    public static String outcomeOf(GatewayResult result) {
        return switch (result) {
            case GatewayResult.Approved ignored -> "approved";
            case GatewayResult.Declined ignored -> "declined";
            case GatewayResult.IdempotencyKeyMismatch ignored -> "key_mismatch";
        };
    }

    private static String tag(TransactionType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }
}
