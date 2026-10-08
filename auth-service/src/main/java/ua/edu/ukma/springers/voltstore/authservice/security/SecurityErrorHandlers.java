package ua.edu.ukma.springers.voltstore.authservice.security;

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
 * Renders security failures raised inside the filter chain (before MVC is reached) in the same
 * RFC 9457 problem format as {@code GlobalExceptionHandler}. The bodies are fixed strings on purpose.
 */
public class SecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final String serviceName;

    public SecurityErrorHandlers(String serviceName) {
        this.serviceName = serviceName;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Authentication required");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        write(response, HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "Access denied");
    }

    private void write(HttpServletResponse response, HttpStatus status, String errorType, String title, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(String.format(
                "{\"type\":\"https://voltstore.com/errors/%s\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\","
                        + "\"timestamp\":\"%s\",\"service\":\"%s\"}",
                errorType, title, status.value(), detail, Instant.now(), serviceName));
    }
}
