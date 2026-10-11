package ua.edu.ukma.springers.voltstore.catalog.controllers;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.*;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Renders every error as an RFC 9457 problem with the platform-wide shape:
 * {@code type}, {@code title}, {@code status}, {@code detail}, {@code timestamp}, {@code service}
 * (plus {@code errors} for field validation failures).
 * <p>
 * 400 = the request is malformed or breaks a field rule; 422 = the request is well-formed but refers to
 * something that does not exist or cannot be done.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final String SERVICE_NAME = "catalog-service";
    private static final String ERROR_TYPE_BASE = "https://voltstore.com/errors/";
    private static final String VALIDATION_DETAIL = "One or more fields are invalid.";

    @ExceptionHandler({ProductNotFoundException.class, CategoryNotFoundException.class})
    public ProblemDetail handleNotFound(RuntimeException ex) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Resource not found", ex.getMessage());
    }

    @ExceptionHandler({CategoryNameAlreadyInUseException.class, CategoryHasProductsException.class})
    public ProblemDetail handleConflict(RuntimeException ex) {
        return problem(HttpStatus.CONFLICT, "conflict", "Conflict", ex.getMessage());
    }

    @ExceptionHandler(CategoryNotFoundForProductException.class)
    public ProblemDetail handleCategoryNotFoundForProduct(CategoryNotFoundForProductException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "unprocessable-entity", "Category not found", ex.getMessage());
    }

    @ExceptionHandler(RequestValidationException.class)
    public ProblemDetail handleRequestValidation(RequestValidationException ex) {
        return validationProblem(ex.getErrors());
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
        return handleExceptionInternal(ex, validationProblem(fieldErrors), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        // A JSON value of the wrong type (e.g. "categoryId": "abc") is reported as a field error,
        // the same way as a Bean Validation failure; anything else is just a malformed body
        String field = mismatchedField(ex);
        ProblemDetail problem = field != null
                ? validationProblem(Map.of(field, List.of("Invalid value")))
                : problem(HttpStatus.BAD_REQUEST, "bad-request", "Malformed request", "Request body is missing or malformed.");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex,
                                                        HttpHeaders headers,
                                                        HttpStatusCode status,
                                                        WebRequest request) {
        // e.g. a non-UUID {id} path variable
        String detail = "Invalid value for parameter '" + ex.getPropertyName() + "'";
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "bad-request", "Invalid parameter", detail);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body,
                                                          HttpHeaders headers,
                                                          HttpStatusCode statusCode,
                                                          WebRequest request) {
        // Framework-built problems (405, 415, ...) get the same type/timestamp/service fields as ours
        if (body instanceof ProblemDetail problem) {
            if (problem.getType() == null || "about:blank".equals(problem.getType().toString())) {
                HttpStatus status = HttpStatus.resolve(statusCode.value());
                String slug = status != null ? status.name().toLowerCase().replace('_', '-') : "error";
                problem.setType(URI.create(ERROR_TYPE_BASE + slug));
            }
            addCommonProperties(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleAllOtherExceptions(Exception ex) {
        // The client only gets a generic message, so the real cause must be recorded here
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal Server Error",
                "An unexpected error occurred.");
    }

    private static ProblemDetail validationProblem(Map<String, List<String>> errors) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation", "Validation failed", VALIDATION_DETAIL);
        problem.setProperty("errors", new TreeMap<>(errors));
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String errorType, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(ERROR_TYPE_BASE + errorType));
        problem.setTitle(title);
        addCommonProperties(problem);
        return problem;
    }

    private static void addCommonProperties(ProblemDetail problem) {
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("service", SERVICE_NAME);
    }

    private static @Nullable String mismatchedField(HttpMessageNotReadableException ex) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof MismatchedInputException mismatch) {
                String path = mismatch.getPath().stream()
                        .map(JacksonException.Reference::getPropertyName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("."));
                return path.isEmpty() ? null : path;
            }
        }
        return null;
    }
}
