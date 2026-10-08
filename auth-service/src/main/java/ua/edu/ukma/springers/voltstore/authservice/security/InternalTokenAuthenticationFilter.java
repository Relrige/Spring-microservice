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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Authenticates calls coming from other VoltStore services (which bypass the API Gateway) by the shared secret in
 * the {@code X-Internal-Token} header. A valid token yields a principal with authority {@code ROLE_SERVICE}.
 * <p>
 * Like the user filter, it never rejects: a missing or wrong token leaves the request anonymous.
 */
@Slf4j
public class InternalTokenAuthenticationFilter extends OncePerRequestFilter {
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    public static final String SERVICE_PRINCIPAL = "internal-service";

    private final byte[] expectedToken;

    public InternalTokenAuthenticationFilter(String expectedToken) {
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(INTERNAL_TOKEN_HEADER);
        if (provided != null) {
            if (MessageDigest.isEqual(expectedToken, provided.getBytes(StandardCharsets.UTF_8))) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new PreAuthenticatedAuthenticationToken(
                        SERVICE_PRINCIPAL, null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE"))));
                SecurityContextHolder.setContext(context);
            } else {
                log.warn("Request with invalid {} header", INTERNAL_TOKEN_HEADER);
            }
        }
        chain.doFilter(request, response);
    }
}
