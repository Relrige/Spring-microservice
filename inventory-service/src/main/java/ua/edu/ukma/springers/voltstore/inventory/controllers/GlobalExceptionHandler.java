package ua.edu.ukma.springers.voltstore.inventory.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockException;

import java.net.URI;
import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail handleInsufficientStock(InsufficientStockException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(URI.create("https://voltstore.com/errors/insufficient-stock"));
        problem.setTitle("Insufficient Stock");

        problem.setProperty("sku", ex.getSku());
        problem.setProperty("availableQuantity", ex.getAvailable());
        problem.setProperty("requestedQuantity", ex.getRequested());
        problem.setProperty("timestamp", Instant.now().toString());
        problem.setProperty("service", "inventory-service");
        return problem;
    }
}