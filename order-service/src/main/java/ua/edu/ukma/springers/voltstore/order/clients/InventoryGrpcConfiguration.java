package ua.edu.ukma.springers.voltstore.order.clients;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ClientStreamTracer;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.stub.MetadataUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import ua.edu.ukma.springers.voltstore.inventory.grpc.v1.StockServiceGrpc;

/**
 * The gRPC channel to Inventory's StockService (inventory_stock.proto, port 9090). Target, deadline and the retry
 * policy come from {@code spring.grpc.client.channel.inventory} in application.yml; this class adds the internal
 * token and a log line per attempt, so the retries the channel makes on its own are visible.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class InventoryGrpcConfiguration {
    static final String CHANNEL = "inventory";
    /** Same secret as the HTTP header X-Internal-Token, sent as gRPC metadata. */
    static final Metadata.Key<String> INTERNAL_TOKEN = Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER);

    @Bean(destroyMethod = "shutdown")
    ManagedChannel inventoryChannel(GrpcChannelFactory channels) {
        return channels.createChannel(CHANNEL);
    }

    @Bean
    StockServiceGrpc.StockServiceBlockingStub inventoryBlockingStub(ManagedChannel inventoryChannel,
                                                                     InternalTokenProperties token) {
        return StockServiceGrpc.newBlockingStub(intercepted(inventoryChannel, token));
    }

    @Bean
    StockServiceGrpc.StockServiceStub inventoryStub(ManagedChannel inventoryChannel, InternalTokenProperties token) {
        return StockServiceGrpc.newStub(intercepted(inventoryChannel, token));
    }

    private static Channel intercepted(Channel channel, InternalTokenProperties token) {
        Metadata headers = new Metadata();
        headers.put(INTERNAL_TOKEN, token.token());
        return ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers),
                new AttemptLogging());
    }

    /**
     * One log line per attempt of a call. The retry policy works below the interceptors (one logical call, several
     * transport streams), so the lines come from a stream tracer, which gRPC creates for every attempt.
     */
    static final class AttemptLogging implements ClientInterceptor {
        @Override
        public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options,
                                                   Channel next) {
            String name = method.getBareMethodName();
            return next.newCall(method, options.withStreamTracerFactory(new ClientStreamTracer.Factory() {
                @Override
                public ClientStreamTracer newClientStreamTracer(ClientStreamTracer.StreamInfo info, Metadata headers) {
                    int attempt = info.getPreviousAttempts() + 1;
                    if (attempt > 1) {
                        log.warn("gRPC Inventory {}: attempt {} (retry {} of the call)", name, attempt, attempt - 1);
                    } else {
                        log.debug("gRPC Inventory {}: attempt 1", name);
                    }
                    return new ClientStreamTracer() {
                        @Override
                        public void streamClosed(Status status) {
                            if (!status.isOk()) {
                                log.warn("gRPC Inventory {}: attempt {} failed with {}{}", name, attempt,
                                        status.getCode(), status.getDescription() == null ? ""
                                                : " (" + status.getDescription() + ")");
                            }
                        }
                    };
                }
            }));
        }
    }
}
