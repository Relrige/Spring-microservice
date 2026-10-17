package ua.edu.ukma.springers.voltstore.order.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CheckoutProperties.class)
public class CheckoutConfig {

    // A bean, so tests can fix "now" (placement time, payment window)
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
