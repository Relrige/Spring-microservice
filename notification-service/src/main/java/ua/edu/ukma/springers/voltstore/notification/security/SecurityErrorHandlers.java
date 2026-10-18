package ua.edu.ukma.springers.voltstore.notification.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Renders security failures raised inside the filter chain in the shared RFC 9457 problem format (same body as in
 * the other services). The bodies are fixed strings on purpose.
 * <p>
 * Every caller is anonymous here, so Spring Security reports a denied path through the
 * {@link AuthenticationEntryPoint}. That is answered with {@code 403} as well, not {@code 401}: there is no way to
 * authenticate to Notification, so "authentication required" would be misleading.
 */
public class SecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final String serviceName;

    public SecurityErrorHandlers(String serviceName) {
        this.serviceName = serviceName;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        writeForbidden(response);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        writeForbidden(response);
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        HttpStatus status = HttpStatus.FORBIDDEN;
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(String.format(
                "{\"type\":\"https://voltstore.com/errors/forbidden\",\"title\":\"Forbidden\",\"status\":%d,"
                        + "\"detail\":\"Access denied\",\"timestamp\":\"%s\",\"service\":\"%s\"}",
                status.value(), Instant.now(), serviceName));
    }
}
