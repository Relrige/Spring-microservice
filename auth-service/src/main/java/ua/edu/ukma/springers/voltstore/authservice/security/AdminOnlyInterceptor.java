package ua.edu.ukma.springers.voltstore.authservice.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserRole;
import ua.edu.ukma.springers.voltstore.authservice.exceptions.ForbiddenException;

/**
 * Allows a request through only if the API Gateway marked the caller as {@link UserRole#ADMIN}.
 * The gateway validates the JWT and forwards the trusted {@code X-User-Role} header; this service does not
 * validate tokens itself, so it must never be reachable by clients bypassing the gateway.
 * Runs before argument resolution, so unauthorized callers get 403 even for an invalid body.
 */
@Component
public class AdminOnlyInterceptor implements HandlerInterceptor {
    public static final String USER_ROLE_HEADER = "X-User-Role";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!UserRole.ADMIN.name().equals(request.getHeader(USER_ROLE_HEADER))) {
            throw new ForbiddenException();
        }
        return true;
    }
}
