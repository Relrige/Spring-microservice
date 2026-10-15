package ua.edu.ukma.springers.voltstore.apigateway.routing;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.server.mvc.config.FilterProperties;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.config.RouteProperties;
import ua.edu.ukma.springers.voltstore.apigateway.security.UserRole;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteAccessPolicyTest {

    @Test
    void validRoute_isParsed() {
        RouteAccessPolicy policy = policy(route("r", Map.of("authMode", "REQUIRED", "roles", "ADMIN, CUSTOMER"), true));

        assertThat(policy.forRoute("r"))
                .contains(new RouteAccess(AuthMode.REQUIRED, Set.of(UserRole.ADMIN, UserRole.CUSTOMER)));
    }

    @Test
    void routeWithoutTrustBoundaryFilter_failsStartup() {
        assertThatThrownBy(() -> policy(route("r", Map.of("authMode", "PUBLIC"), false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("TrustBoundary");
    }

    @Test
    void routeWithoutAuthMode_failsStartup() {
        assertThatThrownBy(() -> policy(route("r", Map.of(), true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("authMode");
    }

    @Test
    void unknownAuthModeOrRole_failsStartup() {
        assertThatThrownBy(() -> policy(route("r", Map.of("authMode", "SOMETIMES"), true)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> policy(route("r", Map.of("authMode", "REQUIRED", "roles", "ROOT"), true)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rolesOnNonRequiredRoute_failStartup() {
        assertThatThrownBy(() -> policy(route("r", Map.of("authMode", "OPTIONAL", "roles", "ADMIN"), true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not REQUIRED");
    }

    @Test
    void duplicateRouteId_failsStartup() {
        RouteProperties route = route("r", Map.of("authMode", "PUBLIC"), true);

        assertThatThrownBy(() -> policy(route, route)).isInstanceOf(IllegalStateException.class);
    }

    private static RouteAccessPolicy policy(RouteProperties... routes) {
        GatewayMvcProperties properties = new GatewayMvcProperties();
        properties.getRoutes().addAll(List.of(routes));
        return new RouteAccessPolicy(properties);
    }

    private static RouteProperties route(String id, Map<String, Object> metadata, boolean withFilter) {
        RouteProperties route = new RouteProperties();
        route.setId(id);
        route.setMetadata(metadata);
        if (withFilter) {
            route.setFilters(List.of(new FilterProperties("TrustBoundary")));
        }
        return route;
    }
}
