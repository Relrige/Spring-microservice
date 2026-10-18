package ua.edu.ukma.springers.voltstore.order.security;

import org.springframework.beans.factory.annotation.Value;
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
 * Stateless security based solely on trusted headers. Only registered customers can order (decision 2026-10-16):
 * every customer route needs role {@code CUSTOMER}, and a customer reaches only their own orders (checked in the
 * service, see {@code OrderAccess}). Workers have the fulfillment queue and dispatch. Order has no internal routes,
 * so there is no internal-token filter here; the token is only sent on outgoing calls. Everything not listed is
 * denied, and the role check runs before the request body is parsed.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final String CUSTOMER = UserRole.CUSTOMER.name();
    private static final String WORKER = UserRole.INVENTORY_WORKER.name();

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   @Value("${spring.application.name}") String serviceName) {
        SecurityErrorHandlers errorHandlers = new SecurityErrorHandlers(serviceName);

        // Created here, not declared as a bean, so Spring Boot does not register it a second time
        TrustedHeaderAuthenticationFilter userFilter = new TrustedHeaderAuthenticationFilter();

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
                        // Worker routes. The queue must come before GET /orders/{orderId}, which would otherwise
                        // also match "/orders/fulfillment-queue" (the first matching rule wins)
                        .requestMatchers(HttpMethod.GET, "/orders/fulfillment-queue").hasRole(WORKER)
                        .requestMatchers(HttpMethod.POST, "/orders/{orderId}/ship").hasRole(WORKER)
                        // Customer routes: only registered customers can order; ownership is checked in the service
                        .requestMatchers(HttpMethod.POST, "/orders/validate").hasRole(CUSTOMER)
                        .requestMatchers(HttpMethod.POST, "/orders").hasRole(CUSTOMER)
                        .requestMatchers(HttpMethod.POST, "/orders/{orderId}/pay").hasRole(CUSTOMER)
                        .requestMatchers(HttpMethod.GET, "/orders").hasRole(CUSTOMER)
                        .requestMatchers(HttpMethod.GET, "/orders/{orderId}").hasRole(CUSTOMER)
                        .requestMatchers(HttpMethod.DELETE, "/orders/{orderId}").hasRole(CUSTOMER)
                        // Fail closed: a route nobody wrote a rule for is unreachable until a rule is added
                        .anyRequest().denyAll())
                .addFilterBefore(userFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
