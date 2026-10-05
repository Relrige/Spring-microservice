//package ua.edu.ukma.springers.voltstore.order.exceptions;
//
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ProblemDetail;
//import org.springframework.web.bind.annotation.ExceptionHandler;
//import org.springframework.web.bind.annotation.RestControllerAdvice;
//import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
//
//import java.net.URI;
//import java.time.Instant;
//
//@RestControllerAdvice
//public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
//
//    @ExceptionHandler(CheckoutNotValidException.class)
//    public ProblemDetail handleCheckoutNotValid(CheckoutNotValidException ex) {
//        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Checkout validation failed due to product status.");
//        problem.setType(URI.create("https://voltstore.com/errors/checkout-invalid"));
//        problem.setTitle("Invalid Checkout Items");
//
//        problem.setProperty("notActiveProducts", ex.getNotActiveProducts());
//        problem.setProperty("outOfStockProducts", ex.getOutOfStockProducts());
//        problem.setProperty("timestamp", Instant.now().toString());
//        problem.setProperty("service", "order-service");
//
//        return problem;
//    }
//
//    @ExceptionHandler(InventoryUnavailableException.class)
//    public ProblemDetail handleInventoryUnavailable(InventoryUnavailableException ex) {
//        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
//        problem.setType(URI.create("https://voltstore.com/errors/inventory-unavailable"));
//        problem.setTitle("Inventory Service Unavailable");
//        problem.setProperty("timestamp", Instant.now().toString());
//        problem.setProperty("service", "order-service");
//        return problem;
//    }
//
//    @ExceptionHandler(Exception.class)
//    public ProblemDetail handleGenericException(Exception ex) {
//        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred in Order Service.");
//        problem.setType(URI.create("https://voltstore.com/errors/internal-error"));
//        problem.setTitle("Internal Server Error");
//        problem.setProperty("timestamp", Instant.now().toString());
//        return problem;
//    }
//}