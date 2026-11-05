package ua.edu.ukma.springers.voltstore.inventory.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import ua.edu.ukma.springers.voltstore.inventory.security.InternalTokenProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** The gRPC server's cross-cutting parts: the internal token check and the exception → status mapping. */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class GrpcConfig {
    /** Same secret as the HTTP header X-Internal-Token, sent as gRPC metadata. */
    public static final Metadata.Key<String> INTERNAL_TOKEN =
            Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Every gRPC call needs the internal token (only Order calls this port; the network policy also allows only
     * Order). Health and reflection are not exposed to anyone else either, so they are covered too.
     */
    @Bean
    @GlobalServerInterceptor
    ServerInterceptor internalTokenInterceptor(InternalTokenProperties properties) {
        byte[] expected = properties.token().getBytes(StandardCharsets.UTF_8);
        return new ServerInterceptor() {
            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                              ServerCallHandler<Q, R> next) {
                String provided = headers.get(INTERNAL_TOKEN);
                if (provided == null || !MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8))) {
                    log.warn("gRPC call {} without a valid {}", call.getMethodDescriptor().getFullMethodName(),
                            INTERNAL_TOKEN.name());
                    call.close(Status.UNAUTHENTICATED.withDescription("missing or invalid internal token"),
                            new Metadata());
                    return new ServerCall.Listener<>() {
                    };
                }
                return next.startCall(call, headers);
            }
        };
    }

    /** Exceptions thrown by the unary and server-streaming methods become statuses (see {@link GrpcErrors}). */
    @Bean
    GrpcExceptionHandler stockExceptionHandler() {
        return exception -> {
            io.grpc.StatusException status = GrpcErrors.toStatus(exception).asException();
            if (status.getStatus().getCode() == Status.Code.INTERNAL) {
                log.error("gRPC call failed", exception);
            }
            return status;
        };
    }
}
