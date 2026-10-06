package ua.edu.ukma.springers.voltstore.catalog.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import ua.edu.ukma.springers.voltstore.catalog.exception.DuplicateSkuException;
import ua.edu.ukma.springers.voltstore.catalog.exception.InvalidProductDataException;
import ua.edu.ukma.springers.voltstore.catalog.exception.ProductNotFoundException;

import java.net.URI;
import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ProblemDetail handleProductNotFound(ProductNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(URI.create("https://voltstore.com/errors/not-found"));
        problem.setTitle("Product Not Found");
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("service", "catalog-service");
        return problem;
    }

    @ExceptionHandler(DuplicateSkuException.class)
    public ProblemDetail handleDuplicateSku(DuplicateSkuException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://voltstore.com/errors/conflict"));
        problem.setTitle("Duplicate Resource");
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("service", "catalog-service");
        return problem;
    }

    @ExceptionHandler(InvalidProductDataException.class)
    public ProblemDetail handleInvalidData(InvalidProductDataException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setType(URI.create("https://voltstore.com/errors/bad-request"));
        problem.setTitle("Invalid Product Data");
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("service", "catalog-service");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleAllOtherExceptions(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        problem.setType(URI.create("https://voltstore.com/errors/internal-error"));
        problem.setTitle("Internal Server Error");
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("service", "catalog-service");
        return problem;
    }
}