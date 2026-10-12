package ua.edu.ukma.springers.voltstore.catalog.clients;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import ua.edu.ukma.springers.voltstore.catalog.security.InternalTokenProperties;

import java.net.http.HttpClient;

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
