package ua.edu.ukma.springers.voltstore.payment.controllers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import ua.edu.ukma.springers.voltstore.payment.entities.FailureReason;
import ua.edu.ukma.springers.voltstore.payment.exceptions.NoSuccessfulChargeException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.OrderAlreadyChargedException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.OrderAlreadyRefundedException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentAttemptInProgressException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentDeclinedException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentGatewayTimeoutException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentGatewayUnavailableException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.RefundAmountMismatchException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.RefundRejectedException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maps exceptions to the platform's RFC 9457 problem format. The status codes are part of the payment flow:
 * Order Service lets the customer retry with another card on 402, retries the call on 502/504, and treats 409 for
 * its own order as "already done".
 * <p>
 * Request bodies here contain card data, so no response or log line may repeat a rejected value: validation errors
 * name the field and the rule only.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final String SERVICE_NAME = "payment-service";
    private static final URI VALIDATION_TYPE = URI.create("https://voltstore.com/errors/validation");
    static final String GATEWAY_UNAVAILABLE_DETAIL = "The payment provider could not be reached. Please try again later.";
    static final String GATEWAY_TIMEOUT_DETAIL = "The payment provider did not answer in time. Please try again later.";

    @ExceptionHandler(PaymentDeclinedException.class)
    public ProblemDetail handlePaymentDeclined(PaymentDeclinedException ex) {
        // A business answer, not a fault of the system
        log.info("Payment declined: {}", ex.getReason().code());
        ProblemDetail problem = problem(HttpStatus.PAYMENT_REQUIRED, "payment-declined", "Payment declined",
                ex.getMessage());
        problem.setProperty("reason", ex.getReason().code());
        return problem;
    }

    @ExceptionHandler(OrderAlreadyChargedException.class)
    public ProblemDetail handleOrderAlreadyCharged(OrderAlreadyChargedException ex) {
        return problem(HttpStatus.CONFLICT, "conflict", "Order already charged", ex.getMessage());
    }

    @ExceptionHandler(OrderAlreadyRefundedException.class)
    public ProblemDetail handleOrderAlreadyRefunded(OrderAlreadyRefundedException ex) {
        return problem(HttpStatus.CONFLICT, "conflict", "Order already refunded", ex.getMessage());
    }

    @ExceptionHandler(PaymentAttemptInProgressException.class)
    public ProblemDetail handlePaymentAttemptInProgress(PaymentAttemptInProgressException ex) {
        return problem(HttpStatus.CONFLICT, "conflict", "Payment attempt in progress", ex.getMessage());
    }

    @ExceptionHandler(NoSuccessfulChargeException.class)
    public ProblemDetail handleNoSuccessfulCharge(NoSuccessfulChargeException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "no-successful-charge", "No successful charge",
                ex.getMessage());
    }

    @ExceptionHandler(RefundAmountMismatchException.class)
    public ProblemDetail handleRefundAmountMismatch(RefundAmountMismatchException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "partial-refund", "Only full refunds are supported",
                ex.getMessage());
    }

    @ExceptionHandler(RefundRejectedException.class)
    public ProblemDetail handleRefundRejected(RefundRejectedException ex) {
        log.warn("Refund rejected by the payment provider, answering 502: {}", ex.getMessage());
        ProblemDetail problem = problem(HttpStatus.BAD_GATEWAY, "refund-rejected", "Refund rejected",
                ex.getMessage());
        problem.setProperty("reason", FailureReason.REFUND_REJECTED.code());
        return problem;
    }

    @ExceptionHandler(PaymentGatewayUnavailableException.class)
    public ProblemDetail handleGatewayUnavailable(PaymentGatewayUnavailableException ex) {
        // The cause stays in the log; the caller gets a fixed text without provider details
        log.warn("Payment gateway unavailable, answering 502: {}", ex.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "gateway-unavailable", "Bad Gateway", GATEWAY_UNAVAILABLE_DETAIL);
    }

    @ExceptionHandler(PaymentGatewayTimeoutException.class)
    public ProblemDetail handleGatewayTimeout(PaymentGatewayTimeoutException ex) {
        log.warn("Payment gateway timed out, answering 504: {}", ex.getMessage());
        return problem(HttpStatus.GATEWAY_TIMEOUT, "gateway-timeout", "Gateway Timeout", GATEWAY_TIMEOUT_DETAIL);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        // Only the field name and the rule's message; FieldError.getRejectedValue() could be a card number
        Map<String, List<String>> fieldErrors = new TreeMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.computeIfAbsent(error.getField(), field -> new ArrayList<>())
                    .add(error.getDefaultMessage());
        }
        ProblemDetail problem = ex.getBody();
        problem.setTitle("Validation failed");
        problem.setDetail("One or more fields are invalid.");
        problem.setProperty("errors", fieldErrors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /**
     * Gives every framework-built ProblemDetail (validation, malformed JSON, type mismatch) the shared fields.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
                                                             HttpHeaders headers, HttpStatusCode statusCode,
                                                             WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            problem.setType(VALIDATION_TYPE);
            addCommonProperties(problem);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleAllOtherExceptions(Exception ex) {
        // The client only gets a generic message, so the real cause must be recorded here
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal Server Error",
                "An unexpected error occurred.");
    }

    private ProblemDetail problem(HttpStatus status, String typeSuffix, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://voltstore.com/errors/" + typeSuffix));
        problem.setTitle(title);
        addCommonProperties(problem);
        return problem;
    }

    private void addCommonProperties(ProblemDetail problem) {
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("service", SERVICE_NAME);
    }
}
