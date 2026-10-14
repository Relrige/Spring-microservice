package ua.edu.ukma.springers.voltstore.apigateway.support;

import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.config.PredicateProperties;
import org.springframework.cloud.gateway.server.mvc.config.RouteProperties;
import ua.edu.ukma.springers.voltstore.apigateway.routing.RouteAccess;
import ua.edu.ukma.springers.voltstore.apigateway.routing.RouteAccessPolicy;

import java.util.ArrayList;
import java.util.List;

/**
 * One concrete request per method of every configured route, so tests grow with the route table instead of being
 * copied by hand. Path variables become a fixed UUID; the upstream name is the route id prefix (e.g. {@code catalog}).
 */
public final class RouteSamples {
    public static final String SAMPLE_ID = "3f2c6a52-7b1e-4c55-9d0a-6f1e2b3c4d5e";

    public record Sample(String routeId, String method, String path, String upstream, RouteAccess access) {
    }

    private RouteSamples() {
    }

    public static List<Sample> from(GatewayMvcProperties properties, RouteAccessPolicy policy) {
        List<Sample> samples = new ArrayList<>();
        for (RouteProperties route : properties.getRoutes()) {
            RouteAccess access = policy.forRoute(route.getId()).orElseThrow();
            String upstream = route.getId().substring(0, route.getId().indexOf('-'));
            List<String> methods = values(route, "Method");
            List<String> paths = values(route, "Path");
            for (String method : methods) {
                for (String path : paths) {
                    samples.add(new Sample(route.getId(), method, concrete(path), upstream, access));
                }
            }
        }
        return samples;
    }

    private static List<String> values(RouteProperties route, String predicate) {
        List<String> values = route.getPredicates().stream()
                .filter(p -> predicate.equals(p.getName()))
                .map(PredicateProperties::getArgs)
                .flatMap(args -> args.values().stream())
                .toList();
        if (values.isEmpty()) {
            throw new IllegalStateException("Route " + route.getId() + " has no " + predicate + " predicate");
        }
        return values;
    }

    private static String concrete(String pattern) {
        // "{id:<regex>}" never contains '/', so a greedy match inside one segment covers nested braces like {8}
        return pattern.replaceAll("\\{[^/]+}", SAMPLE_ID);
    }
}
