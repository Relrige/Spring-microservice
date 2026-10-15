package ua.edu.ukma.springers.voltstore.delivery.controllers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.InvalidQueryParameterException;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.InvalidShipmentStateException;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.ShipmentNotFoundException;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.TtnGenerationFailedException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// Copied from inventory-service (keep the copies close, so a fix can be applied to every service)
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final String SERVICE_NAME = "delivery-service";
    private static final URI VALIDATION_TYPE = URI.create("https://voltstore.com/errors/validation");

    @ExceptionHandler(ShipmentNotFoundException.class)
    public ProblemDetail handleShipmentNotFound(ShipmentNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Shipment not found", ex.getMessage());
    }

    // A well-formed request that the shipment's current status does not allow
    @ExceptionHandler(InvalidShipmentStateException.class)
    public ProblemDetail handleInvalidShipmentState(InvalidShipmentStateException ex) {
        return problem(HttpStatus.CONFLICT, "conflict", "Conflict", ex.getMessage());
    }

    // Manual TTN retry rejected by Inventory or the carrier; reason is the stable value the fulfillment queue shows
    @ExceptionHandler(TtnGenerationFailedException.class)
    public ProblemDetail handleTtnGenerationFailed(TtnGenerationFailedException ex) {
        ProblemDetail problem = problem(HttpStatus.FAILED_DEPENDENCY, "ttn-generation-failed",
                "TTN generation failed", ex.getMessage());
        problem.setProperty("reason", ex.getReason());
        return problem;
    }

    @ExceptionHandler(InvalidQueryParameterException.class)
    public ProblemDetail handleInvalidQueryParameter(InvalidQueryParameterException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation", "Validation failed", ex.getMessage());
        problem.setProperty("errors", Map.of(ex.getParameter(), List.of(ex.getMessage())));
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
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

    // A path or query value of the wrong type, e.g. a non-UUID orderId
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException argument
                ? argument.getName() : ex.getPropertyName();
        String requiredType = ex.getRequiredType() == null ? "value" : ex.getRequiredType().getSimpleName();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Invalid value for '" + name + "'.");
        problem.setTitle("Validation failed");
        problem.setProperty("errors", Map.of(String.valueOf(name), List.of("must be a valid " + requiredType)));
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Failed to read request.");
        problem.setTitle("Validation failed");
        problem.setProperty("errors", Map.of("body", List.of("malformed JSON")));
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
