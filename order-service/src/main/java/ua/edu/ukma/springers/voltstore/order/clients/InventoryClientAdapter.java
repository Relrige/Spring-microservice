package ua.edu.ukma.springers.voltstore.order.clients;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import ua.edu.ukma.springers.voltstore.order.clients.HttpClients.InsufficientStockProblem;
import ua.edu.ukma.springers.voltstore.order.clients.HttpClients.InventoryHttpClient;
import ua.edu.ukma.springers.voltstore.order.clients.HttpClients.ReserveRequest;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations;

import java.util.List;
import java.util.UUID;

/**
 * Inventory's reservations (EP-INV-06/07). Both calls may be retried: a repeated reserve for the same order answers
 * {@code 409}, which this adapter reads as "reserved" (the earlier call succeeded and its answer was lost), and a
 * repeated release changes nothing.
 */
@Component
@RequiredArgsConstructor
public class InventoryClientAdapter implements StockReservations {
    static final String NAME = "inventory";

    private final InventoryHttpClient client;

    @Override
    @Retry(name = NAME, fallbackMethod = "reserveUnavailable")
    @CircuitBreaker(name = NAME)
    public ReservationResult reserve(UUID orderId, List<ReservationItem> items) {
        try {
            client.reserve(new ReserveRequest(orderId, items));
            return new Reserved();
        } catch (HttpClientErrorException e) {
            if (ClientFailures.is(e, HttpStatus.CONFLICT)) {
                return new Reserved();
            }
            if (ClientFailures.is(e, HttpStatus.UNPROCESSABLE_ENTITY)) {
                InsufficientStockProblem problem = e.getResponseBodyAs(InsufficientStockProblem.class);
                return new Insufficient(problem == null || problem.insufficientItems() == null
                        ? List.of() : problem.insufficientItems());
            }
            throw e;
        }
    }

    @Override
    @Retry(name = NAME, fallbackMethod = "releaseUnavailable")
    @CircuitBreaker(name = NAME)
    public void release(UUID orderId) {
        client.release(orderId);
    }

    private ReservationResult reserveUnavailable(UUID orderId, List<ReservationItem> items, Throwable failure) {
        throw ClientFailures.unavailable("Inventory", failure);
    }

    private void releaseUnavailable(UUID orderId, Throwable failure) {
        throw ClientFailures.unavailable("Inventory", failure);
    }
}
