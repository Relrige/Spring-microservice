package ua.edu.ukma.springers.voltstore.order.clients;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Authenticates Order as a VoltStore service on its calls to Catalog, Inventory, Payment and Delivery. The
 * customer's or worker's X-User-* headers are never forwarded: the call is made by the service, not on behalf of the
 * user. The token is never logged.
 */
class InternalTokenInterceptor implements ClientHttpRequestInterceptor {
    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final String token;

    InternalTokenInterceptor(String token) {
        this.token = token;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().set(INTERNAL_TOKEN_HEADER, token);
        return execution.execute(request, body);
    }
}
