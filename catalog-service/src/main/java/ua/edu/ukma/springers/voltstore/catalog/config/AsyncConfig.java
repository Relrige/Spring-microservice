package ua.edu.ukma.springers.voltstore.catalog.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

// Lets event publishing run on Spring Boot's task executor instead of the HTTP request thread
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncConfig {
}
