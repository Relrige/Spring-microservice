package ua.edu.ukma.springers.voltstore.order.clients;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.GetStockLevelsRequest;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReleaseReservationRequest;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReleaseResult;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReserveItem;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReserveStockResponse;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.StockLevel;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.StockServiceGrpc;
import ua.edu.ukma.springers.voltstore.order.dto.InsufficientItem;
import ua.edu.ukma.springers.voltstore.order.ports.StockLevels;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Inventory over gRPC (inventory_stock.proto), one call type per operation:
 * <ul>
 *   <li>{@link #reserve}: client streaming, the order's items are streamed and answered once;</li>
 *   <li>{@link #release}: unary;</li>
 *   <li>{@link #releaseAll}: bidirectional streaming, one result per order on the same stream;</li>
 *   <li>{@link #availableQuantities}: server streaming, one level per product.</li>
 * </ul>
 * Retries are the channel's retry policy (status UNAVAILABLE, application.yml); the deadline of every call covers
 * all its attempts. Every operation is idempotent: a repeated reserve for the same order answers ALREADY_RESERVED,
 * which counts as reserved (an earlier attempt succeeded and its answer was lost), and a release changes nothing the
 * second time. The circuit breaker sees each call once, after the retries.
 */
@Component
@RequiredArgsConstructor
public class InventoryGrpcAdapter implements StockReservations, StockLevels {
    static final String NAME = "inventory";
    // The channel's deadline ends every call; this only bounds the wait for an answer that never comes otherwise
    private static final long ANSWER_WAIT_SECONDS = 30;

    private final StockServiceGrpc.StockServiceBlockingStub blocking;
    private final StockServiceGrpc.StockServiceStub async;

    @Override
    @CircuitBreaker(name = NAME, fallbackMethod = "reserveUnavailable")
    public ReservationResult reserve(UUID orderId, List<ReservationItem> items) {
        CompletableFuture<ReserveStockResponse> answer = new CompletableFuture<>();
        StreamObserver<ReserveItem> requests = async.reserveStock(new StreamObserver<>() {
            @Override
            public void onNext(ReserveStockResponse response) {
                answer.complete(response);
            }

            @Override
            public void onError(Throwable t) {
                answer.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                // The single answer came in onNext
            }
        });
        for (ReservationItem item : items) {
            requests.onNext(ReserveItem.newBuilder().setOrderId(orderId.toString())
                    .setProductId(item.productId().toString()).setQuantity(item.quantity()).build());
        }
        requests.onCompleted();

        ReserveStockResponse response = await(answer);
        return switch (response.getOutcome()) {
            case RESERVED, ALREADY_RESERVED -> new Reserved();
            case INSUFFICIENT_STOCK -> new Insufficient(response.getInsufficientItemsList().stream()
                    .map(item -> new InsufficientItem(UUID.fromString(item.getProductId()), item.getRequested(),
                            item.getAvailable()))
                    .toList());
            default -> throw new IllegalStateException("Inventory answered the reservation with outcome "
                    + response.getOutcome());
        };
    }

    @Override
    @CircuitBreaker(name = NAME, fallbackMethod = "releaseUnavailable")
    public void release(UUID orderId) {
        blocking.releaseReservation(ReleaseReservationRequest.newBuilder().setOrderId(orderId.toString()).build());
    }

    @Override
    @CircuitBreaker(name = NAME, fallbackMethod = "releaseAllUnavailable")
    public Map<UUID, String> releaseAll(Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> failed = new LinkedHashMap<>();
        Map<UUID, Boolean> answered = new HashMap<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        StreamObserver<ReleaseReservationRequest> requests = async.releaseReservations(new StreamObserver<>() {
            @Override
            public void onNext(ReleaseResult result) {
                UUID orderId = UUID.fromString(result.getOrderId());
                synchronized (answered) {
                    answered.put(orderId, true);
                    if (!result.getReleased()) {
                        failed.put(orderId, result.getError());
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                done.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                done.complete(null);
            }
        });
        for (UUID orderId : orderIds) {
            requests.onNext(ReleaseReservationRequest.newBuilder().setOrderId(orderId.toString()).build());
        }
        requests.onCompleted();
        await(done);
        synchronized (answered) {
            // Inventory answers every request; a missing answer is reported like a failed release
            orderIds.stream().filter(id -> !answered.containsKey(id))
                    .forEach(id -> failed.put(id, "no answer from Inventory"));
        }
        return failed;
    }

    @Override
    @CircuitBreaker(name = NAME, fallbackMethod = "availableQuantitiesUnavailable")
    public Map<UUID, Integer> availableQuantities(Collection<UUID> productIds) {
        Map<UUID, Integer> available = new LinkedHashMap<>();
        GetStockLevelsRequest request = GetStockLevelsRequest.newBuilder()
                .addAllProductIds(productIds.stream().map(UUID::toString).toList()).build();
        blocking.getStockLevels(request).forEachRemaining((StockLevel level) ->
                available.put(UUID.fromString(level.getProductId()), level.getAvailable()));
        return available;
    }

    private static <T> T await(CompletableFuture<T> answer) {
        try {
            return answer.get(ANSWER_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof StatusRuntimeException status) {
                throw status;
            }
            throw new IllegalStateException("Inventory call failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Inventory", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("No answer from Inventory within " + ANSWER_WAIT_SECONDS + " s", e);
        }
    }

    private ReservationResult reserveUnavailable(UUID orderId, List<ReservationItem> items, Throwable failure) {
        throw ClientFailures.unavailable("Inventory", failure);
    }

    private void releaseUnavailable(UUID orderId, Throwable failure) {
        throw ClientFailures.unavailable("Inventory", failure);
    }

    private Map<UUID, String> releaseAllUnavailable(Collection<UUID> orderIds, Throwable failure) {
        throw ClientFailures.unavailable("Inventory", failure);
    }

    private Map<UUID, Integer> availableQuantitiesUnavailable(Collection<UUID> productIds, Throwable failure) {
        throw ClientFailures.unavailable("Inventory", failure);
    }
}
