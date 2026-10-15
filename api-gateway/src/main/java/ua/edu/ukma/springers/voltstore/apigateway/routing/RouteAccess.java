package ua.edu.ukma.springers.voltstore.apigateway.routing;

import ua.edu.ukma.springers.voltstore.apigateway.security.UserRole;

import java.util.Set;

/**
 * Who may call a route. {@code roles} empty means any authenticated role (or anonymous, for PUBLIC and OPTIONAL).
 */
public record RouteAccess(AuthMode mode, Set<UserRole> roles) {
}
