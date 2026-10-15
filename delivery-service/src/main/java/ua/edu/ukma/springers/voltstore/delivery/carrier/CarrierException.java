package ua.edu.ukma.springers.voltstore.delivery.carrier;

/**
 * The carrier rejected a request or could not be reached. Callers handle it the same way whatever adapter threw it;
 * the message becomes part of the failure reason ({@code carrier_error: <message>}), so it must not contain secrets.
 */
public class CarrierException extends RuntimeException {
    public CarrierException(String message) {
        super(message);
    }

    public CarrierException(String message, Throwable cause) {
        super(message, cause);
    }
}
