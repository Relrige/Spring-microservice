package ua.edu.ukma.springers.voltstore.authservice.config;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Observability task 02: probes and Prometheus scrapes hit {@code /actuator/**} every few seconds; they are not traced,
 * or Tempo would fill with them. The predicate switches the whole observation off, so these requests also leave the
 * {@code http.server.requests} metrics, which keeps latency and error-rate series about business routes only.
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfig {

    @Bean
    ObservationPredicate noActuatorObservations() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext server
                && server.getCarrier().getRequestURI().startsWith("/actuator"));
    }
}
