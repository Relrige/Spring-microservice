package ua.edu.ukma.springers.voltstore.order.clients;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "clients.catalog")
@Data
public class CatalogClientProperties {
    private String url = "http://catalog-service:8081";
}
