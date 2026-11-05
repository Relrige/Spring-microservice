package ua.edu.ukma.springers.voltstore.order.support;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.GetStockLevelsRequest;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.InsufficientItem;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReleaseReservationRequest;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReleaseReservationResponse;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReleaseResult;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReserveItem;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReserveStockResponse;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.StockLevel;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.StockServiceGrpc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Inventory's StockService for Order's tests (inventory_stock.proto), scriptable per method: a fixed answer, a
 * delay, or a status for the next N attempts. Every attempt that reaches it is recorded, so a test can count the
 * retries of the channel's retry policy. Listens on a local port (Netty, the transport Order uses in production).
 */
public class FakeInventory extends StockServiceGrpc.StockServiceImplBase {
    public static final String RESERVE = "ReserveStock";
    public static final String RELEASE = "ReleaseReservation";
    public static final String LEVELS = "GetStockLevels";
    public static final String RELEASE_ALL = "ReleaseReservations";
    private static final Metadata.Key<String> TOKEN = Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER);

    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final Map<String, FailNext> failures = new ConcurrentHashMap<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();
    private final List<List<ReserveItem>> reserved = new CopyOnWriteArrayList<>();
    private final List<UUID> released = new CopyOnWriteArrayList<>();
    private final List<UUID> batchReleased = new CopyOnWriteArrayList<>();
    private final Map<UUID, Integer> levels = new ConcurrentHashMap<>();
    private final Set<UUID> failingInBatch = ConcurrentHashMap.newKeySet();
    private volatile ReserveStockResponse reserveAnswer = outcome(ReserveStockResponse.Outcome.RESERVED);
    private volatile Duration reserveDelay = Duration.ZERO;
    private Server server;

    private record FailNext(Status.Code code, AtomicInteger remaining) {
    }

    /** Starts on a real local port (0 = any free one). */
    public FakeInventory startOnPort(int port) {
        return start(NettyServerBuilder.forPort(port));
    }

    private FakeInventory start(io.grpc.ServerBuilder<?> builder) {
        try {
            server = builder.addService(ServerInterceptors.intercept(this, new TokenRecorder())).build().start();
            return this;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int port() {
        return server.getPort();
    }

    public void stop() {
        server.shutdownNow();
    }

    /** Back to the defaults: everything answers successfully, nothing recorded. */
    public void reset() {
        attempts.clear();
        failures.clear();
        tokens.clear();
        reserved.clear();
        released.clear();
        batchReleased.clear();
        levels.clear();
        failingInBatch.clear();
        reserveAnswer = outcome(ReserveStockResponse.Outcome.RESERVED);
        reserveDelay = Duration.ZERO;
    }

    // --- Scripting ---

    /** The next {@code times} attempts of the method fail with the status (Integer.MAX_VALUE = every attempt). */
    public void failNext(String method, int times, Status.Code code) {
        failures.put(method, new FailNext(code, new AtomicInteger(times)));
    }

    public void failAlways(String method, Status.Code code) {
        failNext(method, Integer.MAX_VALUE, code);
    }

    public void answerReserve(ReserveStockResponse.Outcome outcome) {
        reserveAnswer = outcome(outcome);
    }

    public void answerReserveInsufficient(UUID productId, int requested, int available) {
        reserveAnswer = ReserveStockResponse.newBuilder().setOutcome(ReserveStockResponse.Outcome.INSUFFICIENT_STOCK)
                .addInsufficientItems(InsufficientItem.newBuilder().setProductId(productId.toString())
                        .setRequested(requested).setAvailable(available)).build();
    }

    public void delayReserve(Duration delay) {
        reserveDelay = delay;
    }

    public void stock(UUID productId, int available) {
        levels.put(productId, available);
    }

    /** The batch release reports this order as failed (the stream itself goes on). */
    public void failInBatch(UUID orderId) {
        failingInBatch.add(orderId);
    }

    // --- Recorded ---

    /** Attempts of the method that reached the server, including failed ones and retries. */
    public int attempts(String method) {
        AtomicInteger count = attempts.get(method);
        return count == null ? 0 : count.get();
    }

    public List<List<ReserveItem>> reserveCalls() {
        return List.copyOf(reserved);
    }

    /** Orders released by the unary call, one entry per successful call. */
    public List<UUID> released() {
        return List.copyOf(released);
    }

    /** Orders released on the bidirectional stream, in the order they arrived. */
    public List<UUID> batchReleased() {
        return List.copyOf(batchReleased);
    }

    public List<String> tokens() {
        return List.copyOf(tokens);
    }

    // --- The service ---

    @Override
    public void releaseReservation(ReleaseReservationRequest request,
                                   StreamObserver<ReleaseReservationResponse> response) {
        if (failed(RELEASE, response)) {
            return;
        }
        released.add(UUID.fromString(request.getOrderId()));
        response.onNext(ReleaseReservationResponse.getDefaultInstance());
        response.onCompleted();
    }

    @Override
    public StreamObserver<ReserveItem> reserveStock(StreamObserver<ReserveStockResponse> response) {
        List<ReserveItem> items = new ArrayList<>();
        return new StreamObserver<>() {
            @Override
            public void onNext(ReserveItem item) {
                items.add(item);
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
                if (failed(RESERVE, response)) {
                    return;
                }
                sleep(reserveDelay);
                reserved.add(List.copyOf(items));
                response.onNext(reserveAnswer);
                response.onCompleted();
            }
        };
    }

    @Override
    public void getStockLevels(GetStockLevelsRequest request, StreamObserver<StockLevel> response) {
        if (failed(LEVELS, response)) {
            return;
        }
        for (String id : request.getProductIdsList()) {
            Integer available = levels.get(UUID.fromString(id));
            response.onNext(StockLevel.newBuilder().setProductId(id).setKnown(available != null)
                    .setAvailable(available == null ? 0 : available).build());
        }
        response.onCompleted();
    }

    @Override
    public StreamObserver<ReleaseReservationRequest> releaseReservations(StreamObserver<ReleaseResult> response) {
        if (failed(RELEASE_ALL, response)) {
            return new StreamObserver<>() {
                @Override
                public void onNext(ReleaseReservationRequest value) {
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                }
            };
        }
        return new StreamObserver<>() {
            @Override
            public void onNext(ReleaseReservationRequest request) {
                UUID orderId = UUID.fromString(request.getOrderId());
                ReleaseResult.Builder result = ReleaseResult.newBuilder().setOrderId(request.getOrderId());
                if (failingInBatch.contains(orderId)) {
                    result.setReleased(false).setError("Inventory's database is unavailable");
                } else {
                    batchReleased.add(orderId);
                    result.setReleased(true);
                }
                response.onNext(result.build());
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
                response.onCompleted();
            }
        };
    }

    /** Counts the attempt; answers with the scripted status if one is due. */
    private boolean failed(String method, StreamObserver<?> response) {
        attempts.computeIfAbsent(method, m -> new AtomicInteger()).incrementAndGet();
        FailNext failure = failures.get(method);
        if (failure != null && failure.remaining().getAndDecrement() > 0) {
            response.onError(Status.fromCode(failure.code()).withDescription("scripted failure").asRuntimeException());
            return true;
        }
        return false;
    }

    private static ReserveStockResponse outcome(ReserveStockResponse.Outcome outcome) {
        return ReserveStockResponse.newBuilder().setOutcome(outcome).build();
    }

    private static void sleep(Duration delay) {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final class TokenRecorder implements ServerInterceptor {
        @Override
        public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                          ServerCallHandler<Q, R> next) {
            String token = headers.get(TOKEN);
            tokens.add(token == null ? "" : token);
            return next.startCall(call, headers);
        }
    }
}
