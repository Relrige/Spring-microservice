package ua.edu.ukma.springers.voltstore.order.clients;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ClientsConfiguration.class);

    @Bean
    public CatalogClient catalogClient(CatalogClientProperties properties) {

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        RestClient restClient = RestClient.builder()
                .baseUrl(properties.getUrl())
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
                    if (correlationId != null) {
                        request.getHeaders().add(CORRELATION_ID_HTTP_HEADER, correlationId);
                    }
                    log.info(">>> [OUTGOING HTTP] {} {} | Header {}: {}",
                            request.getMethod(), request.getURI(), CORRELATION_ID_HTTP_HEADER, correlationId);
                    return execution.execute(request, body);
                })
                .build();

        HttpServiceProxyFactory proxyFactory = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build();

        return proxyFactory.createClient(CatalogClient.class);
    }
}