package ua.edu.ukma.springers.voltstore.apigateway.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import ua.edu.ukma.springers.voltstore.apigateway.errors.GatewayProblems;
import ua.edu.ukma.springers.voltstore.apigateway.routing.AuthMode;
import ua.edu.ukma.springers.voltstore.apigateway.routing.RouteAccess;
import ua.edu.ukma.springers.voltstore.apigateway.routing.RouteAccessPolicy;
import ua.edu.ukma.springers.voltstore.apigateway.security.JwtVerifier;
import ua.edu.ukma.springers.voltstore.apigateway.security.VerifiedIdentity;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The request pipeline of the spec, applied to every route: strip client-supplied identity headers, authenticate
 * according to the route's auth mode, authorize by role, set the trusted identity headers and remove
 * {@code Authorization}. A rejected request never reaches the upstream.
 */
@Component
public class TrustBoundaryFilter {
    private static final Logger log = LoggerFactory.getLogger(TrustBoundaryFilter.class);

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";
    /** Data for the service (order emails), never used for authorization; not logged, it is personal data. */
    public static final String USER_EMAIL_HEADER = "X-User-Email";
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    /** Never accepted from clients: identity comes only from a verified JWT, the internal token only from services. */
    static final List<String> STRIPPED_HEADERS = List.of(USER_ID_HEADER, USER_ROLE_HEADER, USER_EMAIL_HEADER, INTERNAL_TOKEN_HEADER);

    private static final String BEARER_PREFIX = "bearer ";

    private final RouteAccessPolicy policy;
    private final JwtVerifier verifier;

    public TrustBoundaryFilter(RouteAccessPolicy policy, JwtVerifier verifier) {
        this.policy = policy;
        this.verifier = verifier;
    }

    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        String routeId = MvcUtils.getAttribute(request, MvcUtils.GATEWAY_ROUTE_ID_ATTR);
        // Fail closed: RouteAccessPolicy already refuses to start with a route that has no rule
        RouteAccess access = policy.forRoute(routeId)
                .orElseThrow(() -> new IllegalStateException("No access rule for route '" + routeId + "'"));

        Optional<VerifiedIdentity> identity = Optional.empty();
        if (access.mode() != AuthMode.PUBLIC) {
            Credentials credentials = Credentials.from(request.headers().header(HttpHeaders.AUTHORIZATION));
            if (credentials.present()) {
                identity = credentials.token().flatMap(verifier::verify);
                if (identity.isEmpty()) {
                    log.debug("Rejected invalid bearer token on route {}", routeId);
                    return GatewayProblems.unauthorized();
                }
            } else if (access.mode() == AuthMode.REQUIRED) {
                return GatewayProblems.unauthorized();
            }
        }

        if (!access.roles().isEmpty() && identity.map(id -> !access.roles().contains(id.role())).orElse(true)) {
            return GatewayProblems.forbidden();
        }

        return next.handle(forwarded(request, identity));
    }

    private static ServerRequest forwarded(ServerRequest request, Optional<VerifiedIdentity> identity) {
        return ServerRequest.from(request).headers(headers -> {
            // HttpHeaders is case-insensitive and remove() drops every value of the header
            STRIPPED_HEADERS.forEach(headers::remove);
            headers.remove(HttpHeaders.AUTHORIZATION);
            identity.ifPresent(id -> {
                headers.set(USER_ID_HEADER, id.userId().toString());
                headers.set(USER_ROLE_HEADER, id.role().name());
                id.email().ifPresent(email -> headers.set(USER_EMAIL_HEADER, email));
            });
        }).build();
    }

    /** What the client sent in {@code Authorization}: nothing, or something that may or may not be a bearer token. */
    private record Credentials(boolean present, Optional<String> token) {

        static Credentials from(List<String> values) {
            if (values.isEmpty()) {
                return new Credentials(false, Optional.empty());
            }
            if (values.size() > 1) {
                return new Credentials(true, Optional.empty());
            }
            String value = values.getFirst().trim();
            if (!value.toLowerCase(Locale.ROOT).startsWith(BEARER_PREFIX)) {
                return new Credentials(true, Optional.empty());
            }
            return new Credentials(true, Optional.of(value.substring(BEARER_PREFIX.length()).trim()));
        }
    }
}
