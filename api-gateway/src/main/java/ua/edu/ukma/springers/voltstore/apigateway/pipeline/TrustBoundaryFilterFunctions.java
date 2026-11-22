package ua.edu.ukma.springers.voltstore.apigateway.pipeline;

import org.springframework.cloud.gateway.server.mvc.common.MvcUtils;
import org.springframework.cloud.gateway.server.mvc.filter.SimpleFilterSupplier;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import ua.edu.ukma.springers.voltstore.apigateway.ratelimit.RateLimitFilter;

/**
 * Makes the {@link TrustBoundaryFilter} usable from the YAML route definitions as {@code filters: - TrustBoundary}.
 * Gateway MVC discovers YAML filters as static methods (through a {@link SimpleFilterSupplier} bean), so the method
 * looks the Spring bean up per request, the same way the framework's own filters reach their beans.
 */
public final class TrustBoundaryFilterFunctions {
    /** Name used in YAML; {@link ua.edu.ukma.springers.voltstore.apigateway.routing.RouteAccessPolicy} requires it. */
    public static final String FILTER_NAME = "TrustBoundary";

    private TrustBoundaryFilterFunctions() {
    }

    /** Rate limit first (task 14), so a flood costs no JWT verification; then the trust boundary. */
    public static HandlerFilterFunction<ServerResponse, ServerResponse> trustBoundary() {
        return (request, next) -> {
            ApplicationContext context = MvcUtils.getApplicationContext(request);
            TrustBoundaryFilter trustBoundary = context.getBean(TrustBoundaryFilter.class);
            return context.getBean(RateLimitFilter.class)
                    .filter(request, allowed -> trustBoundary.filter(allowed, next));
        };
    }

    public static class FilterSupplier extends SimpleFilterSupplier {

        public FilterSupplier() {
            super(TrustBoundaryFilterFunctions.class);
        }
    }
}
