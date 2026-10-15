package ua.edu.ukma.springers.voltstore.delivery.clients;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import ua.edu.ukma.springers.voltstore.delivery.security.InternalTokenAuthenticationFilter;

import java.io.IOException;

/**
 * Authenticates Delivery as a VoltStore service. Registered only on the RestClient that targets Inventory; the
 * worker's X-User-Id / X-User-Role are never forwarded (the call is made by the service, not on behalf of the
 * user). The token is never logged.
 */
class InternalTokenInterceptor implements ClientHttpRequestInterceptor {
    private final String token;

    InternalTokenInterceptor(String token) {
        this.token = token;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().set(InternalTokenAuthenticationFilter.INTERNAL_TOKEN_HEADER, token);
        return execution.execute(request, body);
    }
}
