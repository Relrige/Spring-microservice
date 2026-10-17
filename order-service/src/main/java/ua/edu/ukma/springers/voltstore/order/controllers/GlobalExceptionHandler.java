package ua.edu.ukma.springers.voltstore.order.controllers;

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
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.exceptions.IneligibleItemsException;
import ua.edu.ukma.springers.voltstore.order.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.order.exceptions.InvalidOrderStateException;
import ua.edu.ukma.springers.voltstore.order.exceptions.MissingCustomerEmailException;
import ua.edu.ukma.springers.voltstore.order.exceptions.OrderNotFoundException;
import ua.edu.ukma.springers.voltstore.order.exceptions.PaymentAttemptInProgressException;
import ua.edu.ukma.springers.voltstore.order.exceptions.PaymentDeclinedException;
import ua.edu.ukma.springers.voltstore.order.exceptions.PaymentWindowExpiredException;
import ua.edu.ukma.springers.voltstore.order.exceptions.TtnNotGeneratedException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maps exceptions to the platform's RFC 9457 problem format (same shape as the other services). Answers of the
 * downstream services are translated, never passed through: a downstream {@code 5xx} becomes {@code 503} with a fixed
 * text, and hosts, statuses and causes stay in the log. Validation errors name the field and the rule only, never the
 * rejected value (the payment request contains card data).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final String SERVICE_NAME = "order-service";
    private static final URI VALIDATION_TYPE = URI.create("https://voltstore.com/errors/validation");
    static final String SERVICE_UNAVAILABLE_DETAIL =
            "The order cannot be processed right now because a required service is unavailable. Please try again later.";
    static final String RETRY_AFTER_SECONDS = "10";

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleOrderNotFound(OrderNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Order not found", ex.getMessage());
    }

    @ExceptionHandler(IneligibleItemsException.class)
    public ProblemDetail handleIneligibleItems(IneligibleItemsException ex) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "ineligible-items", "Items cannot be ordered",
                ex.getMessage());
        problem.setProperty("ineligibleItems", ex.getIneligibleItems());
        return problem;
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail handleInsufficientStock(InsufficientStockException ex) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-stock", "Insufficient stock",
                ex.getMessage());
        problem.setProperty("insufficientItems", ex.getInsufficientItems());
        return problem;
    }

    @ExceptionHandler(InvalidOrderStateException.class)
    public ProblemDetail handleInvalidOrderState(InvalidOrderStateException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "conflict", "Order state conflict", ex.getMessage());
        problem.setProperty("orderStatus", ex.getStatus());
        return problem;
    }

    @ExceptionHandler(PaymentWindowExpiredException.class)
    public ProblemDetail handlePaymentWindowExpired(PaymentWindowExpiredException ex) {
        return problem(HttpStatus.CONFLICT, "payment-window-expired", "Payment window expired", ex.getMessage());
    }

    @ExceptionHandler(PaymentAttemptInProgressException.class)
    public ProblemDetail handlePaymentAttemptInProgress(PaymentAttemptInProgressException ex) {
        return problem(HttpStatus.CONFLICT, "conflict", "Payment attempt in progress", ex.getMessage());
    }

    @ExceptionHandler(PaymentDeclinedException.class)
    public ProblemDetail handlePaymentDeclined(PaymentDeclinedException ex) {
        // A business answer, not a fault of the system
        log.info("Payment declined: {}", ex.getReason());
        ProblemDetail problem = problem(HttpStatus.PAYMENT_REQUIRED, "payment-declined", "Payment declined",
                ex.getMessage());
        problem.setProperty("reason", ex.getReason());
        return problem;
    }

    @ExceptionHandler(TtnNotGeneratedException.class)
    public ProblemDetail handleTtnNotGenerated(TtnNotGeneratedException ex) {
        return problem(HttpStatus.CONFLICT, "conflict", "TTN not generated", ex.getMessage());
    }

    @ExceptionHandler(MissingCustomerEmailException.class)
    public ProblemDetail handleMissingCustomerEmail(MissingCustomerEmailException ex) {
        // Not the customer's fault: the gateway or Auth does not pass the email on (decision D1)
        log.error("A customer request has no X-User-Email header: check that Auth puts the email claim into the JWT "
                + "and the API Gateway forwards it");
        return problem(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Authentication required");
    }

    @ExceptionHandler(DependencyUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleDependencyUnavailable(DependencyUnavailableException ex) {
        // The cause stays in the log; the client gets a fixed text without hosts, statuses or causes
        log.error("{} - answering 503", ex.getMessage(), ex.getCause());
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable", "Service Unavailable",
                SERVICE_UNAVAILABLE_DETAIL);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(problem);
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
