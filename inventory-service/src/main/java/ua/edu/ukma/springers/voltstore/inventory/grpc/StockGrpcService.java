package ua.edu.ukma.springers.voltstore.inventory.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.grpc.server.service.GrpcService;
import ua.edu.ukma.springers.voltstore.inventory.dto.InsufficientItem;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.InsufficientStockForReservationException;
import ua.edu.ukma.springers.voltstore.inventory.exceptions.ReservationAlreadyExistsException;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.GetStockLevelsRequest;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReleaseReservationRequest;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReleaseReservationResponse;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReleaseResult;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReserveItem;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReserveStockResponse;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.StockLevel;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.StockServiceGrpc;
import ua.edu.ukma.springers.voltstore.inventory.services.ReservationService;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inventory's gRPC API (inventory_stock.proto), served on port 9090 for Order. The same operations as the HTTP
 * reservation endpoints (EP-INV-06/07), each in one of the four gRPC call types. Domain and validation failures are
 * turned into statuses by {@link GrpcErrors}.
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class StockGrpcService extends StockServiceGrpc.StockServiceImplBase {
    static final int MAX_ITEMS = 100;

    private final ReservationService reservations;
    private final StockService stock;

    /** Unary: release one order's reservation (idempotent). */
    @Override
    public void releaseReservation(ReleaseReservationRequest request,
                                   StreamObserver<ReleaseReservationResponse> response) {
        reservations.release(GrpcErrors.uuid("order_id", request.getOrderId()));
        response.onNext(ReleaseReservationResponse.getDefaultInstance());
        response.onCompleted();
    }

    /** Client streaming: collect the order's items, reserve them all or nothing when the client completes. */
    @Override
    public StreamObserver<ReserveItem> reserveStock(StreamObserver<ReserveStockResponse> response) {
        return new StreamObserver<>() {
            private UUID orderId;
            private final Map<UUID, Integer> quantities = new LinkedHashMap<>();
            private Status invalid;

            @Override
            public void onNext(ReserveItem item) {
                if (invalid != null) {
                    return;
                }
                try {
                    UUID itemOrder = GrpcErrors.uuid("order_id", item.getOrderId());
                    UUID productId = GrpcErrors.uuid("product_id", item.getProductId());
                    if (orderId != null && !orderId.equals(itemOrder)) {
                        throw GrpcErrors.invalid("all items must have the same order_id");
                    }
                    if (item.getQuantity() < 1) {
                        throw GrpcErrors.invalid("quantity must be at least 1");
                    }
                    if (quantities.putIfAbsent(productId, item.getQuantity()) != null) {
                        throw GrpcErrors.invalid("product " + productId + " is listed twice");
                    }
                    if (quantities.size() > MAX_ITEMS) {
                        throw GrpcErrors.invalid("at most " + MAX_ITEMS + " items");
                    }
                    orderId = itemOrder;
                } catch (io.grpc.StatusRuntimeException e) {
                    // The rest of the stream is read but ignored; the error is the answer once the client completes
                    invalid = e.getStatus();
                }
            }

            @Override
            public void onError(Throwable t) {
                log.debug("Client cancelled the reservation stream of order {}: {}", orderId, t.toString());
            }

            @Override
            public void onCompleted() {
                if (invalid == null && orderId == null) {
                    invalid = Status.INVALID_ARGUMENT.withDescription("at least one item is required");
                }
                if (invalid != null) {
                    response.onError(invalid.asRuntimeException());
                    return;
                }
                try {
                    response.onNext(reserve());
                    response.onCompleted();
                } catch (RuntimeException e) {
                    response.onError(GrpcErrors.toStatus(e).asRuntimeException());
                }
            }

            private ReserveStockResponse reserve() {
                try {
                    reservations.reserve(orderId, quantities);
                    return ReserveStockResponse.newBuilder().setOutcome(ReserveStockResponse.Outcome.RESERVED).build();
                } catch (ReservationAlreadyExistsException e) {
                    return ReserveStockResponse.newBuilder()
                            .setOutcome(ReserveStockResponse.Outcome.ALREADY_RESERVED).build();
                } catch (InsufficientStockForReservationException e) {
                    ReserveStockResponse.Builder answer = ReserveStockResponse.newBuilder()
                            .setOutcome(ReserveStockResponse.Outcome.INSUFFICIENT_STOCK);
                    for (InsufficientItem item : e.getInsufficientItems()) {
                        answer.addInsufficientItems(ua.edu.ukma.springers.voltstore.inventory.grpc.v1.InsufficientItem
                                .newBuilder().setProductId(item.productId().toString())
                                .setRequested(item.requested()).setAvailable(item.available()));
                    }
                    return answer.build();
                }
            }
        };
    }

    /** Server streaming: one level per requested product, in the requested order. */
    @Override
    public void getStockLevels(GetStockLevelsRequest request, StreamObserver<StockLevel> response) {
        if (request.getProductIdsCount() > MAX_ITEMS) {
            throw GrpcErrors.invalid("at most " + MAX_ITEMS + " product ids");
        }
        List<UUID> productIds = request.getProductIdsList().stream()
                .map(id -> GrpcErrors.uuid("product_ids", id)).toList();
        Map<UUID, Integer> available = stock.availableQuantities(productIds);
        for (UUID productId : productIds) {
            Integer quantity = available.get(productId);
            response.onNext(StockLevel.newBuilder().setProductId(productId.toString())
                    .setKnown(quantity != null).setAvailable(quantity == null ? 0 : quantity).build());
        }
        response.onCompleted();
    }

    /** Bidirectional: release each order as its request arrives and answer at once; one failure ends nothing. */
    @Override
    public StreamObserver<ReleaseReservationRequest> releaseReservations(StreamObserver<ReleaseResult> response) {
        return new StreamObserver<>() {
            @Override
            public void onNext(ReleaseReservationRequest request) {
                ReleaseResult.Builder result = ReleaseResult.newBuilder().setOrderId(request.getOrderId());
                try {
                    reservations.release(GrpcErrors.uuid("order_id", request.getOrderId()));
                    result.setReleased(true);
                } catch (RuntimeException e) {
                    log.warn("Releasing the reservation of order {} failed: {}", request.getOrderId(), e.toString());
                    result.setReleased(false).setError(GrpcErrors.toStatus(e).getDescription() == null
                            ? e.toString() : GrpcErrors.toStatus(e).getDescription());
                }
                response.onNext(result.build());
            }

            @Override
            public void onError(Throwable t) {
                log.debug("Client cancelled the release stream: {}", t.toString());
            }

            @Override
            public void onCompleted() {
                response.onCompleted();
            }
        };
    }
}
