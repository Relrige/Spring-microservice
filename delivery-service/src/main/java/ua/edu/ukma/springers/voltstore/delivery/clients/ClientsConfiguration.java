package ua.edu.ukma.springers.voltstore.delivery.clients;

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
    public InventoryClient inventoryClient(InventoryClientProperties properties, InternalTokenProperties internalToken) {
        return createInventoryClient(properties, internalToken.token());
    }

    // Also used by tests to build the client against WireMock without a Spring context
    static InventoryClient createInventoryClient(InventoryClientProperties properties, String internalToken) {
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
                .requestInterceptor(new CorrelationIdInterceptor())
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build()
                .createClient(InventoryClient.class);
    }
}
