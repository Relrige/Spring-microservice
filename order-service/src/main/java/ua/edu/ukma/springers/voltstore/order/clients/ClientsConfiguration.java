package ua.edu.ukma.springers.voltstore.order.clients;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import ua.edu.ukma.springers.voltstore.order.clients.HttpClients.CatalogHttpClient;
import ua.edu.ukma.springers.voltstore.order.clients.HttpClients.DeliveryHttpClient;
import ua.edu.ukma.springers.voltstore.order.clients.HttpClients.InventoryHttpClient;
import ua.edu.ukma.springers.voltstore.order.clients.HttpClients.PaymentHttpClient;

import java.net.http.HttpClient;

/**
 * One {@link RestClient} per service, each with its own timeouts and the internal token. User headers are never
 * forwarded. The observation registry makes every call a client span and adds the W3C {@code traceparent} header
 * (observability task 02); without a registry (slim test contexts) the calls are simply not traced.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ClientsProperties.class, InternalTokenProperties.class})
public class ClientsConfiguration {

    private final ObservationRegistry observationRegistry;

    ClientsConfiguration(ObjectProvider<ObservationRegistry> observationRegistry) {
        this.observationRegistry = observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP);
    }

    @Bean
    CatalogHttpClient catalogHttpClient(ClientsProperties clients, InternalTokenProperties token) {
        return create(CatalogHttpClient.class, clients.catalog(), token);
    }

    @Bean
    InventoryHttpClient inventoryHttpClient(ClientsProperties clients, InternalTokenProperties token) {
        return create(InventoryHttpClient.class, clients.inventory(), token);
    }

    @Bean
    PaymentHttpClient paymentHttpClient(ClientsProperties clients, InternalTokenProperties token) {
        return create(PaymentHttpClient.class, clients.payment(), token);
    }

    @Bean
    DeliveryHttpClient deliveryHttpClient(ClientsProperties clients, InternalTokenProperties token) {
        return create(DeliveryHttpClient.class, clients.delivery(), token);
    }

    private <T> T create(Class<T> type, ClientsProperties.Endpoint endpoint, InternalTokenProperties token) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(endpoint.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(endpoint.readTimeout());

        RestClient restClient = RestClient.builder()
                .baseUrl(endpoint.url())
                .requestFactory(requestFactory)
                .requestInterceptor(new InternalTokenInterceptor(token.token()))
                .observationRegistry(observationRegistry)
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build().createClient(type);
    }
}
