package ua.edu.ukma.springers.voltstore.inventory.clients;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import ua.edu.ukma.springers.voltstore.inventory.security.InternalTokenAuthenticationFilter;

import java.io.IOException;

/**
 * Authenticates Inventory as a VoltStore service. Registered only on the RestClient that targets Catalog; the
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
