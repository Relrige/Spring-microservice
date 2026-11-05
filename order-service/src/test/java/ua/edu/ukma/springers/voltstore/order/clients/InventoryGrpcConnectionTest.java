package ua.edu.ukma.springers.voltstore.order.clients;

import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.grpc.client.autoconfigure.CompositeChannelFactoryAutoConfiguration;
import org.springframework.boot.grpc.client.autoconfigure.GrpcClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ua.edu.ukma.springers.voltstore.order.exceptions.DependencyUnavailableException;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations;
import ua.edu.ukma.springers.voltstore.order.support.FakeInventory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retries on real connection failures (task grpc-order-inventory): nothing listens on Inventory's port, so every
 * attempt ends with UNAVAILABLE from the transport (connection refused), not from a scripted server.
 */
class InventoryGrpcConnectionTest {

    @SpringBootTest(classes = {
            InventoryGrpcConfiguration.class,
            InventoryGrpcAdapter.class,
            InventoryGrpcConnectionTest.Properties.class,
            GrpcClientAutoConfiguration.class,
            CompositeChannelFactoryAutoConfiguration.class,
            SslAutoConfiguration.class,
            AopAutoConfiguration.class,
            CircuitBreakerAutoConfiguration.class
    })
    @ExtendWith(OutputCaptureExtension.class)
    abstract static class Base {
        static final int PORT = freePort();

        @Autowired
        StockReservations reservations;
    }

    @EnableConfigurationProperties(InternalTokenProperties.class)
    static class Properties {
    }

    /** The retry policy exactly as in application.yml. */
    @Nested
    class InventoryNeverComesUp extends Base {
        @DynamicPropertySource
        static void channel(DynamicPropertyRegistry registry) {
            registry.add("spring.grpc.client.channel.inventory.target", () -> "localhost:" + PORT);
        }

        @Test
        void everyAttemptIsRefused_fourAttempts_thenDependencyUnavailable(CapturedOutput output) {
            assertThatThrownBy(() -> reservations.release(UUID.randomUUID()))
                    .isInstanceOfSatisfying(DependencyUnavailableException.class,
                            e -> assertThat(e.getService()).isEqualTo("Inventory"));

            assertThat(output).contains(
                    "gRPC Inventory ReleaseReservation: attempt 1 failed with UNAVAILABLE",
                    "gRPC Inventory ReleaseReservation: attempt 2 (retry 1 of the call)",
                    "gRPC Inventory ReleaseReservation: attempt 3 (retry 2 of the call)",
                    "gRPC Inventory ReleaseReservation: attempt 4 (retry 3 of the call)",
                    "gRPC Inventory ReleaseReservation: attempt 4 failed with UNAVAILABLE");
            assertThat(output).doesNotContain("attempt 5");
        }
    }

    /**
     * Inventory starts 300 ms after the first attempt. After a refused connection the channel waits about 1 s
     * (0.8-1.2 s) before it connects again, and attempts made meanwhile fail at once. The production back-off waits
     * are random up to 0.3 + 0.6 + 1.2 s, so all four attempts can be spent before that reconnect; this test waits
     * longer between attempts (random up to 1 s, 2 s, 4 s, 4 s) so a retry reliably lands after it.
     */
    @Nested
    class InventoryComesUpLate extends Base {
        @DynamicPropertySource
        static void channel(DynamicPropertyRegistry registry) {
            registry.add("spring.grpc.client.channel.inventory.target", () -> "localhost:" + PORT);
            registry.add("spring.grpc.client.channel.inventory.default.deadline", () -> "15s");
            // An element of a list property is replaced as a whole, so the whole method config is set here
            String method = "spring.grpc.client.channel.inventory.service-config.method[0].";
            registry.add(method + "name[0].service", () -> "voltstore.inventory.v1.StockService");
            String retry = "spring.grpc.client.channel.inventory.service-config.method[0].retry.";
            registry.add(retry + "max-attempts", () -> "5");
            registry.add(retry + "initial-backoff", () -> "1s");
            registry.add(retry + "max-backoff", () -> "4s");
            registry.add(retry + "backoff-multiplier", () -> "2");
            registry.add(retry + "retryable-status-codes", () -> "UNAVAILABLE");
        }

        @Test
        void theCallSucceedsOnceInventoryListens(CapturedOutput output) throws Exception {
            FakeInventory inventory = new FakeInventory();
            CompletableFuture<Void> started = CompletableFuture.runAsync(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                inventory.startOnPort(PORT);
            });
            UUID orderId = UUID.randomUUID();
            try {
                reservations.release(orderId);

                started.get(5, TimeUnit.SECONDS);
                assertThat(inventory.released()).containsExactly(orderId);
                assertThat(output).contains("gRPC Inventory ReleaseReservation: attempt 1 failed with UNAVAILABLE",
                        "gRPC Inventory ReleaseReservation: attempt 2 (retry 1 of the call)");
            } finally {
                started.get(5, TimeUnit.SECONDS);
                inventory.stop();
            }
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
