package ua.edu.ukma.springers.voltstore.inventory.controllers;

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
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockForReservationException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.PackagingNotConfiguredException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.ReservationAlreadyExistsException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.StockItemNotFoundException;
import ua.edu.ukma.springers.voltstore.inventory.paging.InvalidQueryParameterException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final String SERVICE_NAME = "inventory-service";
    private static final URI VALIDATION_TYPE = URI.create("https://voltstore.com/errors/validation");

    @ExceptionHandler(StockItemNotFoundException.class)
    public ProblemDetail handleStockItemNotFound(StockItemNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Stock item not found", ex.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail handleInsufficientStock(InsufficientStockException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-stock", "Insufficient stock", ex.getMessage());
    }

    @ExceptionHandler(InsufficientStockForReservationException.class)
    public ProblemDetail handleInsufficientStockForReservation(InsufficientStockForReservationException ex) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-stock",
                "Insufficient stock", ex.getMessage());
        problem.setProperty("insufficientItems", ex.getInsufficientItems());
        return problem;
    }

    @ExceptionHandler(ReservationAlreadyExistsException.class)
    public ProblemDetail handleReservationAlreadyExists(ReservationAlreadyExistsException ex) {
        return problem(HttpStatus.CONFLICT, "conflict", "Reservation already exists", ex.getMessage());
    }

    @ExceptionHandler(PackagingNotConfiguredException.class)
    public ProblemDetail handlePackagingNotConfigured(PackagingNotConfiguredException ex) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "packaging-not-configured",
                "Packaging not configured", ex.getMessage());
        problem.setProperty("reason", "packaging_data_missing");
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
