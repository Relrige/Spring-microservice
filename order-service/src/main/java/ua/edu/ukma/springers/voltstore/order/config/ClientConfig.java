    package ua.edu.ukma.springers.voltstore.order.config;

    import org.slf4j.MDC;
    import org.springframework.beans.factory.annotation.Value;
    import org.springframework.context.annotation.Bean;
    import org.springframework.context.annotation.Configuration;
    import org.springframework.http.client.ClientHttpRequestInterceptor;
    import org.springframework.http.client.JdkClientHttpRequestFactory;
    import org.springframework.web.client.RestClient;
    import org.springframework.web.client.support.RestClientAdapter;
    import org.springframework.web.service.invoker.HttpServiceProxyFactory;
    import ua.edu.ukma.springers.voltstore.order.client.CatalogClient;

    import java.net.http.HttpClient;
    import java.time.Duration;
    import java.util.UUID;

    @Configuration
    public class ClientConfig {

        @Value("${catalog.client.base-url}")
        private String catalogServiceUrl;

        @Bean
        public ClientHttpRequestInterceptor correlationIdInterceptor() {
            return (request, body, execution) -> {
                String correlationId = MDC.get("correlationId");
                if (correlationId == null || correlationId.isBlank()) {
                    correlationId = UUID.randomUUID().toString();
                }
                request.getHeaders().add("X-Correlation-Id", correlationId);
                return execution.execute(request, body);
            };
        }

        @Bean
        public CatalogClient catalogClient(ClientHttpRequestInterceptor correlationIdInterceptor) {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();

            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(Duration.ofSeconds(3));

            RestClient restClient = RestClient.builder()
                    .baseUrl(catalogServiceUrl)
                    .requestFactory(requestFactory)
                    .requestInterceptor(correlationIdInterceptor)
                    .build();

            HttpServiceProxyFactory factory = HttpServiceProxyFactory
                    .builderFor(RestClientAdapter.create(restClient))
                    .build();

            return factory.createClient(CatalogClient.class);
        }
    }