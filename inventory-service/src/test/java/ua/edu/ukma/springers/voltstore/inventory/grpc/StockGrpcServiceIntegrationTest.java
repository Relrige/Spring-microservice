package ua.edu.ukma.springers.voltstore.inventory.grpc;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ua.edu.ukma.springers.voltstore.inventory.KafkaTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.GetStockLevelsRequest;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReleaseReservationRequest;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReleaseResult;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReserveItem;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.ReserveStockResponse;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.StockLevel;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.StockServiceGrpc;
import ua.edu.ukma.springers.voltstore.inventory.repositories.StockItemRepository;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;
import ua.edu.ukma.springers.voltstore.inventory.support.CatalogKnowsEveryProduct;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The four methods of inventory_stock.proto against the real StockService (task grpc-order-inventory), over the
 * in-process transport: the same server, interceptors and services as on port 9090, without a network port.
 */
@SpringBootTest(properties = "spring.grpc.server.inprocess.name=stock-grpc-test")
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class, CatalogKnowsEveryProduct.class})
class StockGrpcServiceIntegrationTest {
    private static final String TOKEN = "internal-token-internal-token-1234567";

    @Autowired
    private StockService stockService;

    @Autowired
    private StockItemRepository stockItems;

    @Autowired
    private JdbcTemplate jdbc;

    private ManagedChannel channel;
    private StockServiceGrpc.StockServiceBlockingStub blocking;
    private StockServiceGrpc.StockServiceStub async;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM stock_item_reservations");
        jdbc.update("DELETE FROM stock_items");
        channel = InProcessChannelBuilder.forName("stock-grpc-test").build();
        Metadata headers = new Metadata();
        headers.put(GrpcConfig.INTERNAL_TOKEN, TOKEN);
        blocking = StockServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        async = StockServiceGrpc.newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
    }

    @AfterEach
    void closeChannel() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    // --- Client streaming: ReserveStock ---

    @Test
    void reserveStock_streamedItems_areReservedAllAtOnce_andARepeatIsAlreadyReserved() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID laptop = stocked(5);
        UUID phone = stocked(2);

        ReserveStockResponse first = reserve(item(orderId, laptop, 3), item(orderId, phone, 2));
        assertThat(first.getOutcome()).isEqualTo(ReserveStockResponse.Outcome.RESERVED);
        assertAvailable(laptop, 2);
        assertAvailable(phone, 0);

        ReserveStockResponse repeat = reserve(item(orderId, laptop, 3), item(orderId, phone, 2));
        assertThat(repeat.getOutcome()).isEqualTo(ReserveStockResponse.Outcome.ALREADY_RESERVED);
        assertAvailable(laptop, 2);
    }

    @Test
    void reserveStock_shortItems_reserveNothing_andListEveryShortItem() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID enough = stocked(5);
        UUID shortOne = stocked(1);

        ReserveStockResponse answer = reserve(item(orderId, enough, 2), item(orderId, shortOne, 4));

        assertThat(answer.getOutcome()).isEqualTo(ReserveStockResponse.Outcome.INSUFFICIENT_STOCK);
        assertThat(answer.getInsufficientItemsList()).singleElement().satisfies(item -> {
            assertThat(item.getProductId()).isEqualTo(shortOne.toString());
            assertThat(item.getRequested()).isEqualTo(4);
            assertThat(item.getAvailable()).isEqualTo(1);
        });
        assertAvailable(enough, 5);
    }

    @Test
    void reserveStock_itemsOfTwoOrders_isInvalidArgument() {
        UUID product = stocked(5);
        assertThatThrownBy(() -> reserve(item(UUID.randomUUID(), product, 1), item(UUID.randomUUID(), product, 1)))
                .hasCauseInstanceOf(StatusRuntimeException.class)
                .cause().satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    // --- Unary: ReleaseReservation ---

    @Test
    void releaseReservation_returnsTheStock_andIsIdempotent() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID product = stocked(4);
        reserve(item(orderId, product, 3));
        assertAvailable(product, 1);

        blocking.releaseReservation(release(orderId));
        assertAvailable(product, 4);
        blocking.releaseReservation(release(orderId));
        assertAvailable(product, 4);
    }

    // --- Server streaming: GetStockLevels ---

    @Test
    void getStockLevels_streamsOneLevelPerProduct_inTheRequestedOrder() {
        UUID first = stocked(7);
        UUID unknown = UUID.randomUUID();
        UUID second = stocked(0);

        List<StockLevel> levels = new ArrayList<>();
        blocking.getStockLevels(GetStockLevelsRequest.newBuilder().addProductIds(first.toString())
                .addProductIds(unknown.toString()).addProductIds(second.toString()).build()).forEachRemaining(levels::add);

        assertThat(levels).extracting(StockLevel::getProductId, StockLevel::getKnown, StockLevel::getAvailable)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(first.toString(), true, 7),
                        org.assertj.core.groups.Tuple.tuple(unknown.toString(), false, 0),
                        org.assertj.core.groups.Tuple.tuple(second.toString(), true, 0));
    }

    // --- Bidirectional streaming: ReleaseReservations ---

    @Test
    void releaseReservations_answersEachRequestOnTheSameStream_andAFailureEndsNothing() throws Exception {
        UUID product = stocked(6);
        UUID orderA = UUID.randomUUID();
        UUID orderB = UUID.randomUUID();
        reserve(item(orderA, product, 2));
        reserve(item(orderB, product, 3));
        assertAvailable(product, 1);

        List<ReleaseResult> results = new ArrayList<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        StreamObserver<ReleaseReservationRequest> requests = async.releaseReservations(new StreamObserver<>() {
            @Override
            public void onNext(ReleaseResult result) {
                results.add(result);
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
        requests.onNext(release(orderA));
        requests.onNext(ReleaseReservationRequest.newBuilder().setOrderId("not-a-uuid").build());
        requests.onNext(release(orderB));
        requests.onCompleted();
        done.get(10, TimeUnit.SECONDS);

        assertThat(results).extracting(ReleaseResult::getOrderId, ReleaseResult::getReleased).containsExactly(
                org.assertj.core.groups.Tuple.tuple(orderA.toString(), true),
                org.assertj.core.groups.Tuple.tuple("not-a-uuid", false),
                org.assertj.core.groups.Tuple.tuple(orderB.toString(), true));
        assertThat(results.get(1).getError()).contains("order_id must be a UUID");
        assertAvailable(product, 6);
    }

    // --- Security ---

    @Test
    void aCallWithoutTheInternalToken_isUnauthenticated() {
        StockServiceGrpc.StockServiceBlockingStub anonymous = StockServiceGrpc.newBlockingStub(channel);
        assertThatThrownBy(() -> anonymous.releaseReservation(release(UUID.randomUUID())))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    private ReserveStockResponse reserve(ReserveItem... items) throws Exception {
        CompletableFuture<ReserveStockResponse> answer = new CompletableFuture<>();
        StreamObserver<ReserveItem> stream = async.reserveStock(new StreamObserver<>() {
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
            }
        });
        for (ReserveItem item : items) {
            stream.onNext(item);
        }
        stream.onCompleted();
        return answer.get(10, TimeUnit.SECONDS);
    }

    private static ReserveItem item(UUID orderId, UUID productId, int quantity) {
        return ReserveItem.newBuilder().setOrderId(orderId.toString()).setProductId(productId.toString())
                .setQuantity(quantity).build();
    }

    private static ReleaseReservationRequest release(UUID orderId) {
        return ReleaseReservationRequest.newBuilder().setOrderId(orderId.toString()).build();
    }

    private UUID stocked(int quantity) {
        UUID productId = UUID.randomUUID();
        stockService.ensureStockItem(productId);
        if (quantity > 0) {
            stockService.adjustStock(productId, quantity);
        }
        return productId;
    }

    private void assertAvailable(UUID productId, int available) {
        assertThat(stockItems.findById(productId).orElseThrow().getAvailableQuantity()).isEqualTo(available);
    }
}
