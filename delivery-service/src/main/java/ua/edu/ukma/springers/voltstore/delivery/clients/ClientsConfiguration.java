package ua.edu.ukma.springers.voltstore.delivery.clients;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import ua.edu.ukma.springers.voltstore.delivery.security.InternalTokenProperties;

import java.net.http.HttpClient;

// Same structure as Inventory's ClientsConfiguration (its Catalog client)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InventoryClientProperties.class)
public class ClientsConfiguration {

    @Bean
    public InventoryClient inventoryClient(InventoryClientProperties properties, InternalTokenProperties internalToken,
                                ObjectProvider<ObservationRegistry> observationRegistry) {
        return createInventoryClient(properties, internalToken.token(),
                observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    // Also used by tests to build the client against WireMock without a Spring context (no tracing)
    static InventoryClient createInventoryClient(InventoryClientProperties properties, String internalToken) {
        return createInventoryClient(properties, internalToken, ObservationRegistry.NOOP);
    }

    // The registry makes every call a client span with the W3C traceparent header (observability task 02)
    static InventoryClient createInventoryClient(InventoryClientProperties properties, String internalToken,
                                     ObservationRegistry observationRegistry) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        // A RestClient of its own, so the internal token is added only to calls that target Inventory
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.url())
                .requestFactory(requestFactory)
                .requestInterceptor(new InternalTokenInterceptor(internalToken))
                .observationRegistry(observationRegistry)
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build()
                .createClient(InventoryClient.class);
    }
}
