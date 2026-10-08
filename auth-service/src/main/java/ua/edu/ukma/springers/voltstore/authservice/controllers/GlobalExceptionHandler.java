package ua.edu.ukma.springers.voltstore.authservice.controllers;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import ua.edu.ukma.springers.voltstore.authservice.exceptions.EmailNotUniqueException;
import ua.edu.ukma.springers.voltstore.authservice.exceptions.InvalidCredentialsException;
import ua.edu.ukma.springers.voltstore.authservice.exceptions.InvalidUserRoleException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(EmailNotUniqueException.class)
    public ProblemDetail handleEmailNotUnique(EmailNotUniqueException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://voltstore.com/errors/conflict"));
        problem.setTitle("Email is not unique");
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("service", "auth-service");
        return problem;
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
        // Body is deliberately fixed: it must not reveal whether the email exists
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setType(URI.create("https://voltstore.com/errors/unauthorized"));
        problem.setTitle("Unauthorized");
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("service", "auth-service");
        return problem;
    }

    @ExceptionHandler(InvalidUserRoleException.class)
    public ProblemDetail handleInvalidUserRole(InvalidUserRoleException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more fields are invalid.");
        problem.setType(URI.create("https://voltstore.com/errors/validation"));
        problem.setTitle("Validation failed");
        problem.setProperty("errors", Map.of("role", List.of(ex.getMessage())));
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("service", "auth-service");
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
        problem.setType(URI.create("https://voltstore.com/errors/validation"));
        problem.setTitle("Validation failed");
        problem.setDetail("One or more fields are invalid.");
        problem.setProperty("errors", fieldErrors);
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("service", "auth-service");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleAllOtherExceptions(Exception ex) {
        // The client only gets a generic message, so the real cause must be recorded here
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        problem.setType(URI.create("https://voltstore.com/errors/internal-error"));
        problem.setTitle("Internal Server Error");
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("service", "auth-service");
        return problem;
    }
}