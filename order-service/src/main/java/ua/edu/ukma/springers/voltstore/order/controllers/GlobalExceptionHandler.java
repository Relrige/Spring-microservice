package ua.edu.ukma.springers.voltstore.order.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ua.edu.ukma.springers.voltstore.order.exceptions.CheckoutNotValidException;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(CheckoutNotValidException.class)
    public ProblemDetail handleCheckoutNotValid(CheckoutNotValidException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "Some items in the cart are not eligible for checkout");
        problemDetail.setTitle("Checkout Validation Error");
        problemDetail.setType(URI.create("urn:voltstore:error:checkout-invalid"));

        problemDetail.setProperty("notActiveProducts", ex.getNotActiveProducts());
        problemDetail.setProperty("outOfStockProducts", ex.getOutOfStockProducts());

        return problemDetail;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneralException(Exception ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected internal server error occurred");
        problemDetail.setTitle("Internal Server Error");
        problemDetail.setType(URI.create("urn:voltstore:error:internal"));
        return problemDetail;
    }
}
