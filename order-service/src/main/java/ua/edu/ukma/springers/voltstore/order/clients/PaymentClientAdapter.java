package ua.edu.ukma.springers.voltstore.order.clients;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import ua.edu.ukma.springers.voltstore.order.clients.HttpClients.ChargeRequest;
import ua.edu.ukma.springers.voltstore.order.clients.HttpClients.PaymentHttpClient;
import ua.edu.ukma.springers.voltstore.order.clients.HttpClients.PaymentResponse;
import ua.edu.ukma.springers.voltstore.order.clients.HttpClients.RefundRequest;
import ua.edu.ukma.springers.voltstore.order.ports.Payments;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Payment Service (EP-PAY-01/02). <b>No retry:</b> Payment repeats a gateway call whose outcome is unknown itself,
 * with the same idempotency key, and answers {@code 502}/{@code 504} only when that did not settle it; repeating the
 * whole request from here would only make the customer wait longer. A circuit breaker still stops calling a Payment
 * that keeps failing. Every outcome is a result, so the business code decides what the customer sees.
 * <p>
 * Payment's two {@code 409}s mean different things and are told apart by the problem title (a contract with
 * Payment Service): "Order already charged" counts as paid, "Payment attempt in progress" must be repeated with the
 * same card.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentClientAdapter implements Payments {
    static final String NAME = "payment";
    static final String ALREADY_CHARGED_TITLE = "Order already charged";
    static final String ATTEMPT_IN_PROGRESS_TITLE = "Payment attempt in progress";
    static final String REFUND_REJECTED_REASON = "refund_rejected";

    private final PaymentHttpClient client;

    @Override
    @CircuitBreaker(name = NAME, fallbackMethod = "chargeUnavailable")
    public ChargeResult charge(UUID orderId, BigDecimal amount, String currency, PaymentCard card) {
        try {
            PaymentResponse response = client.charge(new ChargeRequest(orderId, amount, currency, card));
            return new Paid(response == null ? null : response.transactionId());
        } catch (HttpClientErrorException e) {
            if (ClientFailures.is(e, HttpStatus.PAYMENT_REQUIRED)) {
                return new Declined(ClientFailures.problem(e).map(p -> property(p, "reason")).orElse("declined"));
            }
            if (ClientFailures.is(e, HttpStatus.CONFLICT)) {
                String title = ClientFailures.problem(e).map(ProblemDetail::getTitle).orElse("");
                if (ALREADY_CHARGED_TITLE.equals(title)) {
                    return new AlreadyCharged();
                }
                if (ATTEMPT_IN_PROGRESS_TITLE.equals(title)) {
                    return new AttemptInProgress();
                }
            }
            // 400 means Order sent something Payment rejects, 401/403 a wrong token: bugs or configuration
            log.error("Payment answered the charge of order {} with an unexpected {}", orderId, e.getStatusCode());
            return new ChargeUnavailable(ClientFailures.describe(e));
        }
    }

    @Override
    @CircuitBreaker(name = NAME, fallbackMethod = "refundUnavailable")
    public RefundResult refund(UUID orderId, BigDecimal amount, String currency) {
        try {
            client.refund(new RefundRequest(orderId, amount, currency));
            return new Refunded();
        } catch (HttpClientErrorException e) {
            if (ClientFailures.is(e, HttpStatus.CONFLICT)) {
                return new AlreadyRefunded();
            }
            // 422: no successful charge, or the amount differs from it - a bug or data problem, not retryable by time
            log.error("Payment answered the refund of order {} with an unexpected {}", orderId, e.getStatusCode());
            return new RefundFailed(ClientFailures.describe(e));
        } catch (HttpServerErrorException e) {
            // The provider rejected the refund: a business answer that must not open the circuit for everyone
            if (ClientFailures.problem(e).map(p -> property(p, "reason")).filter(REFUND_REJECTED_REASON::equals)
                    .isPresent()) {
                return new RefundFailed(REFUND_REJECTED_REASON);
            }
            throw e;
        }
    }

    private ChargeResult chargeUnavailable(UUID orderId, BigDecimal amount, String currency, PaymentCard card,
                                           Throwable failure) {
        log.warn("Charge of order {} has no answer: {}", orderId, ClientFailures.describe(failure));
        return new ChargeUnavailable(ClientFailures.describe(failure));
    }

    private RefundResult refundUnavailable(UUID orderId, BigDecimal amount, String currency, Throwable failure) {
        log.warn("Refund of order {} has no answer: {}", orderId, ClientFailures.describe(failure));
        return new RefundFailed(ClientFailures.describe(failure));
    }

    private static String property(ProblemDetail problem, String name) {
        return problem.getProperties() == null ? null : Objects.toString(problem.getProperties().get(name), null);
    }
}
