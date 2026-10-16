package ua.edu.ukma.springers.voltstore.customer.support;

import org.springframework.http.HttpHeaders;

import java.util.UUID;

/** Builds the headers the API Gateway would forward for an authenticated user. */
public final class TrustedHeaders {

    private TrustedHeaders() {
    }

    public static HttpHeaders asUser(String role) {
        return asUser(UUID.randomUUID(), role);
    }

    public static HttpHeaders asUser(UUID userId, String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", userId.toString());
        headers.add("X-User-Role", role);
        return headers;
    }

    public static HttpHeaders asCustomer(UUID customerId) {
        return asUser(customerId, "CUSTOMER");
    }
}
