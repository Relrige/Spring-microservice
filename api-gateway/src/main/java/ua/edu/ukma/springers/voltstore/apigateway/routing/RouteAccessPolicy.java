package ua.edu.ukma.springers.voltstore.apigateway.routing;

import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.config.RouteProperties;
import org.springframework.stereotype.Component;
import ua.edu.ukma.springers.voltstore.apigateway.security.UserRole;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the access rule of every route from its {@code metadata} (keys {@code authMode} and {@code roles}).
 * Spring Cloud Gateway MVC does not expose route metadata at request time, so the request filters look a rule up
 * by route id. The application fails to start if a route has no valid rule, so a route cannot be added without
 * deciding who may call it.
 */
@Component
public class RouteAccessPolicy {
    static final String AUTH_MODE_KEY = "authMode";
    static final String ROLES_KEY = "roles";

    private final Map<String, RouteAccess> rules;

    public RouteAccessPolicy(GatewayMvcProperties properties) {
        Map<String, RouteAccess> parsed = new LinkedHashMap<>();
        for (RouteProperties route : properties.getRoutes()) {
            String id = route.getId();
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("Every gateway route must have an id");
            }
            if (parsed.put(id, parse(id, route.getMetadata())) != null) {
                throw new IllegalStateException("Duplicate gateway route id '" + id + "'");
            }
        }
        this.rules = Collections.unmodifiableMap(parsed);
    }

    public Optional<RouteAccess> forRoute(String routeId) {
        return Optional.ofNullable(rules.get(routeId));
    }

    public Map<String, RouteAccess> all() {
        return rules;
    }

    private static RouteAccess parse(String routeId, Map<String, Object> metadata) {
        Object rawMode = metadata.get(AUTH_MODE_KEY);
        if (rawMode == null) {
            throw new IllegalStateException("Route '" + routeId + "' has no metadata." + AUTH_MODE_KEY);
        }
        AuthMode mode;
        try {
            mode = AuthMode.valueOf(rawMode.toString().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Route '" + routeId + "' has unknown authMode '" + rawMode + "'");
        }

        Set<UserRole> roles = EnumSet.noneOf(UserRole.class);
        Object rawRoles = metadata.get(ROLES_KEY);
        if (rawRoles != null && !rawRoles.toString().isBlank()) {
            for (String name : rawRoles.toString().split(",")) {
                try {
                    roles.add(UserRole.valueOf(name.trim()));
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("Route '" + routeId + "' has unknown role '" + name.trim() + "'");
                }
            }
            if (mode != AuthMode.REQUIRED) {
                throw new IllegalStateException("Route '" + routeId + "' lists roles but its authMode is not REQUIRED");
            }
        }
        return new RouteAccess(mode, Collections.unmodifiableSet(roles));
    }
}
