package ua.edu.ukma.springers.voltstore.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import ua.edu.ukma.springers.voltstore.payment.gateway.GatewayProperties;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfig {
}
