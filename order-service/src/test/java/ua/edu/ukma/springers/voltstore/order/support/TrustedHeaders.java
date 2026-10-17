package ua.edu.ukma.springers.voltstore.order.support;

import org.springframework.http.HttpHeaders;

import java.util.UUID;

/** Builds the headers the API Gateway would forward for an authenticated user. */
public final class TrustedHeaders {
    public static final String EMAIL = "customer@example.com";

    private TrustedHeaders() {
    }

    public static HttpHeaders asUser(String role) {
        return asUser(UUID.randomUUID(), role, EMAIL);
    }

    public static HttpHeaders asCustomer(UUID customerId) {
        return asUser(customerId, "CUSTOMER", EMAIL);
    }

    public static HttpHeaders asUser(UUID userId, String role, String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", userId.toString());
        headers.add("X-User-Role", role);
        if (email != null) {
            headers.add("X-User-Email", email);
        }
        return headers;
    }
}
