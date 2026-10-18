package ua.edu.ukma.springers.voltstore.payment.security;

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
 * Stateless, internal-only security. Every route of this service is called by Order Service with the shared
 * {@code X-Internal-Token}; the API Gateway never routes {@code /payments/**}. There are no user routes, so unlike
 * the other services there is no trusted-header (X-User-*) filter: user headers are ignored and such a caller stays
 * anonymous. The role check runs before the controller, so a rejected caller's request body (card data) is never
 * parsed. Everything not listed is denied.
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

        // Created here, not declared as a bean, so Spring Boot does not register it a second time
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
                        // Internal-only (Order Service): EP-PAY-01 and EP-PAY-02
                        .requestMatchers(HttpMethod.POST, "/payments/charge").hasRole("SERVICE")
                        .requestMatchers(HttpMethod.POST, "/payments/refund").hasRole("SERVICE")
                        // Fail closed: a route nobody wrote a rule for is unreachable until a rule is added
                        .anyRequest().denyAll())
                .addFilterBefore(internalFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
