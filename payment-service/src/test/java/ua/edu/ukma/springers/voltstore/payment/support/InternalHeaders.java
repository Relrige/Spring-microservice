package ua.edu.ukma.springers.voltstore.payment.support;

import org.springframework.http.HttpHeaders;

import java.util.UUID;

/** Builds the headers of an internal caller (Order Service) and, for negative tests, of a gateway-forwarded user. */
public final class InternalHeaders {
    public static final String INTERNAL_TOKEN = "internal-token-internal-token-1234567";

    private InternalHeaders() {
    }

    public static HttpHeaders asInternalService() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Token", INTERNAL_TOKEN);
        return headers;
    }

    public static HttpHeaders asUser(String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", UUID.randomUUID().toString());
        headers.add("X-User-Role", role);
        return headers;
    }
}
