package ua.edu.ukma.springers.voltstore.inventory.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Stateless security based solely on trusted headers: no login form, no sessions, no JWT validation here
 * (the API Gateway validates tokens). Which role may call which route is declared here, in one place; callers
 * without the role are rejected before the controller is reached. Everything not listed is denied.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(InternalTokenProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   InternalTokenProperties internalTokenProperties,
                                                   @Value("${spring.application.name}") String serviceName) {
        SecurityErrorHandlers errorHandlers = new SecurityErrorHandlers(serviceName);

        // The filters are created here, not declared as beans, so Spring Boot does not register them a second time
        TrustedHeaderAuthenticationFilter userFilter = new TrustedHeaderAuthenticationFilter();
        InternalTokenAuthenticationFilter internalFilter = new InternalTokenAuthenticationFilter(internalTokenProperties.token());

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
                        .requestMatchers("/actuator/health/**").permitAll()
                        // Role rules per route: callers without the role are rejected here, before the controller
                        // is reached and before the request body is parsed or validated
                        .requestMatchers(HttpMethod.GET, "/inventory/stock")
                                .hasRole(UserRole.INVENTORY_WORKER.name())
                        .requestMatchers(HttpMethod.PUT, "/inventory/stock/{productId}/packaging")
                                .hasRole(UserRole.INVENTORY_WORKER.name())
                        .requestMatchers(HttpMethod.POST, "/inventory/stock/{productId}/adjust")
                                .hasRole(UserRole.INVENTORY_WORKER.name())
                        // Internal-only (Catalog, via X-Internal-Token); not routed by the API Gateway
                        .requestMatchers(HttpMethod.GET, "/inventory/stock/{productId}/readiness")
                                .hasRole("SERVICE")
                        // Internal-only (Delivery); the PUT on the same path keeps its worker rule above
                        .requestMatchers(HttpMethod.GET, "/inventory/stock/{productId}/packaging")
                                .hasRole("SERVICE")
                        // Internal-only (Order)
                        .requestMatchers(HttpMethod.POST, "/inventory/reservations")
                                .hasRole("SERVICE")
                        .requestMatchers(HttpMethod.POST, "/inventory/reservations/{orderId}/release")
                                .hasRole("SERVICE")
                        // Fail closed: a route nobody wrote a rule for is unreachable until a rule is added
                        .anyRequest().denyAll())
                // Internal token first: if present and valid, the request is a service call and user headers are ignored
                .addFilterBefore(userFilter, AnonymousAuthenticationFilter.class)
                .addFilterBefore(internalFilter, TrustedHeaderAuthenticationFilter.class);
        return http.build();
    }
}
