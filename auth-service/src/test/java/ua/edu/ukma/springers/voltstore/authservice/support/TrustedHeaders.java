package ua.edu.ukma.springers.voltstore.authservice.support;

import org.springframework.http.HttpHeaders;

import java.util.UUID;

/** Builds the headers the API Gateway would forward for an authenticated user or an internal caller. */
public final class TrustedHeaders {
    public static final String INTERNAL_TOKEN = "internal-token-internal-token-1234567";

    private TrustedHeaders() {
    }

    public static HttpHeaders asUser(String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", UUID.randomUUID().toString());
        headers.add("X-User-Role", role);
        return headers;
    }

    public static HttpHeaders asInternalService() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Token", INTERNAL_TOKEN);
        return headers;
    }
}
