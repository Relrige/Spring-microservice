package ua.edu.ukma.springers.voltstore.catalog.security;

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
    private static final String MANAGER = UserRole.CATALOG_MANAGER.name();

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
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        // Internal (EP-CAT-09). Must come before the public "/catalog/products/*" rule below, which
                        // would otherwise also match "/catalog/products/batch-get" (the first matching rule wins)
                        .requestMatchers(HttpMethod.GET, "/catalog/products/batch-get").hasRole("SERVICE")
                        // Public reads; for the product listing the role only changes what is returned
                        .requestMatchers(HttpMethod.GET, "/catalog/products", "/catalog/products/*", "/catalog/categories")
                        .permitAll()
                        // Role rules per route: callers without the role are rejected here, before the controller
                        // is reached and before the request body is parsed or validated. Roles are not
                        // hierarchical: ADMIN has no access to manager routes.
                        .requestMatchers(HttpMethod.POST, "/catalog/products", "/catalog/categories").hasRole(MANAGER)
                        .requestMatchers(HttpMethod.PUT, "/catalog/products/*", "/catalog/categories/*").hasRole(MANAGER)
                        .requestMatchers(HttpMethod.PATCH, "/catalog/products/*/status").hasRole(MANAGER)
                        .requestMatchers(HttpMethod.DELETE, "/catalog/categories/*").hasRole(MANAGER)
                        // Fail closed: a route nobody wrote a rule for is unreachable until a rule is added
                        .anyRequest().denyAll())
                // Internal token first: if present and valid, the request is a service call and user headers are ignored
                .addFilterBefore(userFilter, AnonymousAuthenticationFilter.class)
                .addFilterBefore(internalFilter, TrustedHeaderAuthenticationFilter.class);
        return http.build();
    }
}
