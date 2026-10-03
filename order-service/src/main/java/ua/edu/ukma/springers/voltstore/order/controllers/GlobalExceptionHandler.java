package ua.edu.ukma.springers.voltstore.order.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ua.edu.ukma.springers.voltstore.order.exceptions.CheckoutNotValidException;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problemDetail.setTitle("Validation Error");
        problemDetail.setType(URI.create("urn:voltstore:error:validation"));

        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        error -> error.getDefaultMessage() != null ? error.getDefaultMessage() : "invalid",
                        (msg1, msg2) -> msg1 + ", " + msg2
                ));

        problemDetail.setProperty("invalidFields", fieldErrors);
        return problemDetail;
    }

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
