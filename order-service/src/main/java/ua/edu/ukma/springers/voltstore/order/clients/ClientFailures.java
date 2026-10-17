package ua.edu.ukma.springers.voltstore.order.clients;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;

import java.util.Optional;

/** Shared translation of failed calls, used by the adapters' fallbacks. */
@Slf4j
final class ClientFailures {

    private ClientFailures() {
    }

    /**
     * The final outcome after retries and the circuit breaker: an exception for the caller, logged once. A
     * programming error (an illegal argument) is passed on unchanged instead of being disguised as an outage.
     */
    static RuntimeException unavailable(String service, Throwable failure) {
        if (failure instanceof IllegalArgumentException illegal) {
            return illegal;
        }
        if (failure instanceof HttpClientErrorException clientError
                && (clientError.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)
                || clientError.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN))) {
            // Not an outage: Order is configured wrongly (internal token, or the other service's route rules)
            log.error("{} rejected Order's call with {} - check INTERNAL_TOKEN and {}'s route rules",
                    service, clientError.getStatusCode(), service);
        } else {
            log.warn("{} call failed: {}", service, describe(failure));
        }
        return new DependencyUnavailableException(service, describe(failure), failure);
    }

    /** Status and type only; bodies of other services are not logged (they may contain personal data). */
    static String describe(Throwable failure) {
        if (failure instanceof RestClientResponseException response) {
            return "status " + response.getStatusCode().value();
        }
        return failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
    }

    static boolean is(RestClientResponseException e, HttpStatus status) {
        return e.getStatusCode().isSameCodeAs(status);
    }

    /** The other service's RFC 9457 body, if it sent one. */
    static Optional<ProblemDetail> problem(RestClientResponseException e) {
        try {
            return Optional.ofNullable(e.getResponseBodyAs(ProblemDetail.class));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }
}
