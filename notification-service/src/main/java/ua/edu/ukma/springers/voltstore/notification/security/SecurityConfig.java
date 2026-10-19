package ua.edu.ukma.springers.voltstore.notification.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Notification has no business endpoints and no callers: the gateway never routes to it and no service calls it.
 * Only the probes and Prometheus scraping are open; every other path is denied, so a controller or actuator
 * endpoint added later is never exposed by accident.
 * <p>
 * Unlike the other services there is no trusted-header or internal-token filter: no request carries a user
 * identity, and Notification makes no outgoing HTTP calls (its only outgoing connection is SMTP).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   @Value("${spring.application.name}") String serviceName) {
        SecurityErrorHandlers errorHandlers = new SecurityErrorHandlers(serviceName);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        // Fail closed: a route nobody wrote a rule for is unreachable until a rule is added
                        .anyRequest().denyAll());
        return http.build();
    }
}
