package ua.edu.ukma.springers.voltstore.customer.security;

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
 * Stateless security based solely on trusted headers: no login form, no sessions, no JWT validation here
 * (the API Gateway validates tokens). Which role may call which route is declared here, in one place; callers
 * without the role are rejected before the controller is reached. Everything not listed is denied.
 * <p>
 * Every route belongs to the logged-in customer: the customer ID is always taken from {@code X-User-Id}, never from
 * the path or body, so a customer can only reach their own profile and cart. Customer Service has no internal
 * (service-to-service) routes, so unlike the other services it has no internal-token filter.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final String CUSTOMER = UserRole.CUSTOMER.name();

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   @Value("${spring.application.name}") String serviceName) {
        SecurityErrorHandlers errorHandlers = new SecurityErrorHandlers(serviceName);

        // The filter is created here, not declared as a bean, so Spring Boot does not register it a second time
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
                        // Role rules per route: callers without the role are rejected here, before the controller
                        // is reached and before the request body is parsed or validated
                        // Profile (EP-CUST-01, EP-CUST-02)
                        .requestMatchers(HttpMethod.GET, "/customers/me/profile").hasRole(CUSTOMER)
                        .requestMatchers(HttpMethod.PUT, "/customers/me/profile").hasRole(CUSTOMER)
                        // Whole cart: get, replace, clear (EP-CUST-03, EP-CUST-07, EP-CUST-09) and merge (EP-CUST-08)
                        .requestMatchers(HttpMethod.GET, "/customers/cart").hasRole(CUSTOMER)
                        .requestMatchers(HttpMethod.PUT, "/customers/cart").hasRole(CUSTOMER)
                        .requestMatchers(HttpMethod.DELETE, "/customers/cart").hasRole(CUSTOMER)
                        .requestMatchers(HttpMethod.POST, "/customers/cart/merge").hasRole(CUSTOMER)
                        // Single items (EP-CUST-04 to EP-CUST-06)
                        .requestMatchers(HttpMethod.POST, "/customers/cart/items").hasRole(CUSTOMER)
                        .requestMatchers(HttpMethod.PATCH, "/customers/cart/items/{productId}").hasRole(CUSTOMER)
                        .requestMatchers(HttpMethod.DELETE, "/customers/cart/items/{productId}").hasRole(CUSTOMER)
                        // Fail closed: a route nobody wrote a rule for is unreachable until a rule is added
                        .anyRequest().denyAll())
                .addFilterBefore(userFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
