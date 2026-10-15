package ua.edu.ukma.springers.voltstore.delivery.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.delivery.support.TrustedHeaders;

import java.util.UUID;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The route rules of {@link SecurityConfig}, checked against a probe controller that maps Delivery's three routes
 * and answers 200 when it is reached. The real controllers are covered by {@code RouteSecurityRulesTest}.
 */
@WebMvcTest(controllers = SecurityConfigTest.ProbeController.class)
@Import({SecurityConfig.class, SecurityConfigTest.ProbeController.class})
class SecurityConfigTest {
    private static final String ORDER_ID = UUID.randomUUID().toString();

    @Autowired
    private MockMvc mockMvc;

    static Stream<Arguments> routes() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/delivery/shipments?orderIds=" + ORDER_ID, "SERVICE"),
                Arguments.of(HttpMethod.DELETE, "/delivery/shipments/" + ORDER_ID, "SERVICE"),
                Arguments.of(HttpMethod.POST, "/delivery/orders/" + ORDER_ID + "/ttn/retry", "INVENTORY_WORKER"));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void anonymousCaller_gets401Problem(HttpMethod method, String url, String allowed) throws Exception {
        mockMvc.perform(request(method, url).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Authentication required"))
                .andExpect(jsonPath("$.service").value("delivery-service"));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void allowedCaller_reachesTheController(HttpMethod method, String url, String allowed) throws Exception {
        mockMvc.perform(request(method, url).headers(caller(allowed)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @MethodSource("routes")
    void everyOtherCaller_gets403_includingAdmin(HttpMethod method, String url, String allowed) throws Exception {
        for (String caller : new String[]{"SERVICE", "CUSTOMER", "CATALOG_MANAGER", "INVENTORY_WORKER", "ADMIN"}) {
            if (caller.equals(allowed)) {
                continue;
            }
            // A body is sent too: the role check must happen before anything parses it
            mockMvc.perform(request(method, url).headers(caller(caller))
                            .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value("Access denied"));
        }
    }

    @Test
    void wrongInternalToken_isAnonymous() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Token", "wrong-token-wrong-token-wrong-token-1");

        mockMvc.perform(get("/delivery/shipments").headers(headers)).andExpect(status().isUnauthorized());
    }

    @Test
    void validInternalToken_takesPrecedenceOverUserHeaders() throws Exception {
        HttpHeaders headers = TrustedHeaders.asUser("INVENTORY_WORKER");
        headers.addAll(TrustedHeaders.asInternalService());

        mockMvc.perform(request(HttpMethod.POST, "/delivery/orders/" + ORDER_ID + "/ttn/retry").headers(headers))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthEndpoint_isOpenToAnonymousCallers() throws Exception {
        // No actuator in this slice, so reaching the dispatcher (404) proves security let the call through
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isNotFound());
    }

    @Test
    void unlistedRoute_isDeniedEvenForAdmin() throws Exception {
        mockMvc.perform(get("/delivery/shipments/" + ORDER_ID).headers(TrustedHeaders.asUser("ADMIN")))
                .andExpect(status().isForbidden());
        mockMvc.perform(request(HttpMethod.POST, "/delivery/carrier-webhook").headers(TrustedHeaders.asInternalService()))
                .andExpect(status().isForbidden());
    }

    private static HttpHeaders caller(String role) {
        return role.equals("SERVICE") ? TrustedHeaders.asInternalService() : TrustedHeaders.asUser(role);
    }

    @RestController
    static class ProbeController {
        @GetMapping("/delivery/shipments")
        String shipments() {
            return SecurityContextHolder.getContext().getAuthentication().getName();
        }

        @DeleteMapping("/delivery/shipments/{orderId}")
        void cancel(@PathVariable UUID orderId) {
        }

        @PostMapping("/delivery/orders/{orderId}/ttn/retry")
        void retry(@PathVariable UUID orderId) {
        }
    }
}
