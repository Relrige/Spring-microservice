package ua.edu.ukma.springers.voltstore.authservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserRole;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Authenticates a request from the headers set by the API Gateway after it validated the user's JWT.
 * <p>
 * The filter never rejects a request: missing or malformed headers simply leave it anonymous, and the
 * authorization rules of the filter chain decide what an anonymous caller may do.
 * It is safe only if clients cannot reach the service without passing through the gateway, which must strip any
 * client-supplied copies of these headers.
 */
@Slf4j
public class TrustedHeaderAuthenticationFilter extends OncePerRequestFilter {
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // A caller already authenticated by an earlier filter (e.g. a service with an internal token) wins
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            resolveUser(request).ifPresent(user -> {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new PreAuthenticatedAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()))));
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }

    private Optional<AuthenticatedUser> resolveUser(HttpServletRequest request) {
        String id = request.getHeader(USER_ID_HEADER);
        String role = request.getHeader(USER_ROLE_HEADER);
        if (id == null || id.isBlank() || role == null || role.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new AuthenticatedUser(UUID.fromString(id), UserRole.valueOf(role)));
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring malformed {} / {} headers", USER_ID_HEADER, USER_ROLE_HEADER);
            return Optional.empty();
        }
    }
}
