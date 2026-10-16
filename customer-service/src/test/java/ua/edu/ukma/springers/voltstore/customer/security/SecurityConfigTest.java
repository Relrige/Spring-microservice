package ua.edu.ukma.springers.voltstore.customer.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.customer.support.TrustedHeaders;

import java.util.UUID;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The route rules of {@link SecurityConfig}, checked against a probe controller that maps every Customer route and
 * answers 200 when it is reached. The real controllers are covered by {@code RouteSecurityRulesTest}.
 */
@WebMvcTest(controllers = SecurityConfigTest.ProbeController.class)
@Import({SecurityConfig.class, SecurityConfigTest.ProbeController.class})
class SecurityConfigTest {
    private static final String PRODUCT_ID = UUID.randomUUID().toString();

    @Autowired
    private MockMvc mockMvc;

    static Stream<Arguments> routes() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/customers/me/profile"),
                Arguments.of(HttpMethod.PUT, "/customers/me/profile"),
                Arguments.of(HttpMethod.GET, "/customers/cart"),
                Arguments.of(HttpMethod.PUT, "/customers/cart"),
                Arguments.of(HttpMethod.DELETE, "/customers/cart"),
                Arguments.of(HttpMethod.POST, "/customers/cart/merge"),
                Arguments.of(HttpMethod.POST, "/customers/cart/items"),
                Arguments.of(HttpMethod.PATCH, "/customers/cart/items/" + PRODUCT_ID),
                Arguments.of(HttpMethod.DELETE, "/customers/cart/items/" + PRODUCT_ID));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void anonymousCaller_gets401Problem(HttpMethod method, String url) throws Exception {
        mockMvc.perform(request(method, url).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Authentication required"))
                .andExpect(jsonPath("$.service").value("customer-service"));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void customer_reachesTheController(HttpMethod method, String url) throws Exception {
        mockMvc.perform(request(method, url).headers(TrustedHeaders.asUser("CUSTOMER")))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @MethodSource("routes")
    void everyOtherRole_gets403_includingAdmin(HttpMethod method, String url) throws Exception {
        for (String role : new String[]{"CATALOG_MANAGER", "INVENTORY_WORKER", "ADMIN"}) {
            // A malformed body is sent too: the role check must happen before anything parses it
            mockMvc.perform(request(method, url).headers(TrustedHeaders.asUser(role))
                            .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value("Access denied"));
        }
    }

    @Test
    void principalCarriesTheCustomerIdFromTheHeader() throws Exception {
        UUID customerId = UUID.randomUUID();

        mockMvc.perform(get("/customers/me/profile").headers(TrustedHeaders.asCustomer(customerId)))
                .andExpect(status().isOk())
                .andExpect(content().string(customerId.toString()));
    }

    @Test
    void malformedHeaders_areAnonymous() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "not-a-uuid");
        headers.add("X-User-Role", "CUSTOMER");

        mockMvc.perform(get("/customers/cart").headers(headers)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/customers/cart").headers(TrustedHeaders.asUser("NO_SUCH_ROLE")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsOpen_andUnlistedRouteIsDenied() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isNotFound());
        mockMvc.perform(get("/customers/unlisted").headers(TrustedHeaders.asUser("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @RestController
    static class ProbeController {
        @GetMapping("/customers/me/profile")
        String profile(@AuthenticationPrincipal AuthenticatedUser user) {
            return user.id().toString();
        }

        @RequestMapping(method = RequestMethod.PUT, path = "/customers/me/profile")
        void putProfile() {
        }

        @RequestMapping(method = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.DELETE}, path = "/customers/cart")
        void cart() {
        }

        @RequestMapping(method = RequestMethod.POST, path = {"/customers/cart/merge", "/customers/cart/items"})
        void post() {
        }

        @RequestMapping(method = {RequestMethod.PATCH, RequestMethod.DELETE}, path = "/customers/cart/items/{productId}")
        void item() {
        }

        @GetMapping("/customers/unlisted")
        void unlisted() {
        }
    }
}
