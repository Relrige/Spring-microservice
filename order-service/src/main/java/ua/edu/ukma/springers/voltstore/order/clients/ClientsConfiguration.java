package ua.edu.ukma.springers.voltstore.order.clients;

import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;
import java.time.Duration;

import static ua.edu.ukma.springers.voltstore.order.utils.constants.CorrelationIdKeys.*;

@Configuration
@EnableConfigurationProperties(CatalogClientProperties.class)
public class ClientsConfiguration {
    @Bean
    public CatalogClient catalogClient(
            RestClient.Builder builder,
            CatalogClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        RestClient restClient = builder
                .baseUrl(properties.getUrl())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().add(CORRELATION_ID_HTTP_HEADER, MDC.get(CORRELATION_ID_MDC_KEY));
                    return execution.execute(request, body);
                })
                .build();

        HttpServiceProxyFactory proxyFactory = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build();

        return proxyFactory.createClient(CatalogClient.class);
    }
}
