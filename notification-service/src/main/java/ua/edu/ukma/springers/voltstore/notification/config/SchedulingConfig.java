package ua.edu.ukma.springers.voltstore.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Runs the sender job and the cleanup job (jobs/); the pool size is set in application.yml
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
