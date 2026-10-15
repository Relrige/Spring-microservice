package ua.edu.ukma.springers.voltstore.payment.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentGatewayTimeoutException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Stand-in for a payment provider, used in every environment because the project has no real one. The outcome of a
 * charge is chosen by the card number's last four digits:
 * <ul>
 *   <li>{@code 4242}: approved</li>
 *   <li>{@code 0002}: declined, insufficient funds</li>
 *   <li>{@code 5000}: approved, but the answer is "lost": the charge is stored, then a timeout is reported. A repeat
 *       with the same key returns the approval at once, as a real provider would</li>
 *   <li>any other number: declined</li>
 * </ul>
 * A refund is approved for a charge this mock approved, otherwise rejected.
 * <p>
 * Idempotency keys behave like a real provider's: a key is processed once; a repeat with the same parameters gets
 * the stored result, a repeat with other parameters gets {@link GatewayResult.IdempotencyKeyMismatch}; concurrent
 * requests with one key wait for each other. The store is in memory, so it is per instance and lost on restart:
 * Payment Service must run as a single replica while it uses this mock.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.gateway.provider", havingValue = "mock")
public class MockPaymentGateway implements PaymentGateway {
    static final String APPROVED_CARD_SUFFIX = "4242";
    static final String INSUFFICIENT_FUNDS_CARD_SUFFIX = "0002";
    static final String LOST_ANSWER_CARD_SUFFIX = "5000";

    // The "lost answer" is reported after the gateway time limit, as a real adapter's read timeout would
    private final Duration timeoutDelay;
    // Fingerprints may contain the card number: kept in memory like a provider's records, never logged
    private final Map<String, StoredResult> resultsByKey = new ConcurrentHashMap<>();
    private final Map<String, Object> keyLocks = new ConcurrentHashMap<>();
    private final Set<String> approvedCharges = ConcurrentHashMap.newKeySet();
    private final AtomicInteger approvedChargeCount = new AtomicInteger();
    private final AtomicInteger approvedRefundCount = new AtomicInteger();

    public MockPaymentGateway(GatewayProperties properties) {
        this.timeoutDelay = properties.timeout();
    }

    @Override
    public GatewayResult charge(ChargeCommand command) {
        String fingerprint = String.join("|", "charge", normalized(command.amount()), command.currency(),
                command.card().number());
        Outcome outcome = processOnce(command.idempotencyKey(), fingerprint, () -> decideCharge(command.card()));
        log.info("Mock gateway charge {} {} with {} (key {}): {}{}", command.amount(), command.currency(),
                command.card().masked(), command.idempotencyKey(), outcome.result(),
                outcome.firstTime() ? "" : " (replayed)");

        if (outcome.firstTime() && LOST_ANSWER_CARD_SUFFIX.equals(command.card().lastFour())) {
            sleep(timeoutDelay);
            throw new PaymentGatewayTimeoutException(
                    "Mock gateway: no answer for charge with key " + command.idempotencyKey());
        }
        return outcome.result();
    }

    @Override
    public GatewayResult refund(RefundCommand command) {
        String fingerprint = String.join("|", "refund", command.gatewayTransactionId(),
                normalized(command.amount()), command.currency());
        Outcome outcome = processOnce(command.idempotencyKey(), fingerprint,
                () -> decideRefund(command.gatewayTransactionId()));
        log.info("Mock gateway refund of {} (key {}): {}{}", command.gatewayTransactionId(),
                command.idempotencyKey(), outcome.result(), outcome.firstTime() ? "" : " (replayed)");
        return outcome.result();
    }

    /** How many charges took money; lets tests prove that a repeated or concurrent request charged once. */
    public int approvedChargeCount() {
        return approvedChargeCount.get();
    }

    public int approvedRefundCount() {
        return approvedRefundCount.get();
    }

    private Outcome processOnce(String key, String fingerprint, Supplier<GatewayResult> decision) {
        synchronized (keyLocks.computeIfAbsent(key, k -> new Object())) {
            StoredResult stored = resultsByKey.get(key);
            if (stored != null) {
                GatewayResult result = stored.fingerprint().equals(fingerprint)
                        ? stored.result()
                        : new GatewayResult.IdempotencyKeyMismatch();
                return new Outcome(result, false);
            }
            GatewayResult result = decision.get();
            resultsByKey.put(key, new StoredResult(fingerprint, result));
            return new Outcome(result, true);
        }
    }

    private GatewayResult decideCharge(PaymentCard card) {
        return switch (card.lastFour()) {
            case APPROVED_CARD_SUFFIX, LOST_ANSWER_CARD_SUFFIX -> {
                String id = "mock_ch_" + UUID.randomUUID();
                approvedCharges.add(id);
                approvedChargeCount.incrementAndGet();
                yield new GatewayResult.Approved(id);
            }
            case INSUFFICIENT_FUNDS_CARD_SUFFIX -> new GatewayResult.Declined(DeclineReason.INSUFFICIENT_FUNDS);
            default -> new GatewayResult.Declined(DeclineReason.CARD_DECLINED);
        };
    }

    private GatewayResult decideRefund(String gatewayTransactionId) {
        if (!approvedCharges.contains(gatewayTransactionId)) {
            return new GatewayResult.Declined(DeclineReason.REFUND_REJECTED);
        }
        approvedRefundCount.incrementAndGet();
        return new GatewayResult.Approved("mock_re_" + UUID.randomUUID());
    }

    // 100, 100.0 and 100.00 are the same amount
    private static String normalized(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record StoredResult(String fingerprint, GatewayResult result) {
    }

    private record Outcome(GatewayResult result, boolean firstTime) {
    }
}
