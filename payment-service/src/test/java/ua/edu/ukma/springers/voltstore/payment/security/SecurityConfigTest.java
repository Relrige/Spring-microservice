package ua.edu.ukma.springers.voltstore.payment.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ua.edu.ukma.springers.voltstore.payment.support.InternalHeaders;
import ua.edu.ukma.springers.voltstore.payment.support.PaymentRoutesProbeController;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the internal-only security chain against stand-ins of the charge and refund routes
 * ({@link PaymentRoutesProbeController}).
 */
@WebMvcTest(controllers = PaymentRoutesProbeController.class)
@Import({SecurityConfig.class, PaymentRoutesProbeController.class})
class SecurityConfigTest {
    private static final String VALID_BODY = "{\"orderId\":\"" + UUID.randomUUID() + "\"}";

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {"/payments/charge", "/payments/refund"})
    void internalService_isAllowed(String path) throws Exception {
        mockMvc.perform(postJson(path, VALID_BODY).headers(InternalHeaders.asInternalService()))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/payments/charge", "/payments/refund"})
    void anonymousCaller_getsFixed401Problem(String path) throws Exception {
        mockMvc.perform(postJson(path, VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Authentication required"))
                .andExpect(jsonPath("$.service").value("payment-service"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void wrongToken_isAnonymous() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Token", "wrong-token-wrong-token-wrong-token-1");

        mockMvc.perform(postJson("/payments/charge", VALID_BODY).headers(headers))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CUSTOMER", "CATALOG_MANAGER", "INVENTORY_WORKER"})
    void userHeadersWithoutToken_areIgnored(String role) throws Exception {
        // No user may call Payment Service, not even an admin: there is no trusted-header filter here
        mockMvc.perform(postJson("/payments/charge", VALID_BODY).headers(InternalHeaders.asUser(role)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenWithUserHeaders_isTheInternalService() throws Exception {
        HttpHeaders headers = InternalHeaders.asUser("CUSTOMER");
        headers.addAll(InternalHeaders.asInternalService());

        mockMvc.perform(postJson("/payments/charge", VALID_BODY).headers(headers)).andExpect(status().isOk());
    }

    @Test
    void rejectedCaller_isAnsweredBeforeTheBodyIsValidated() throws Exception {
        // 401, not 400: the body (card data in the real endpoint) is not even parsed for an unknown caller
        mockMvc.perform(postJson("/payments/charge", "{\"orderId\":null}")).andExpect(status().isUnauthorized());
        mockMvc.perform(postJson("/payments/charge", "{\"orderId\":null}").headers(InternalHeaders.asInternalService()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void protectedRouteWithWrongHttpMethod_isDenied() throws Exception {
        mockMvc.perform(get("/payments/charge").headers(InternalHeaders.asInternalService()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Access denied"));
    }

    @Test
    void unlistedRoute_isDeniedEvenForInternalService() throws Exception {
        mockMvc.perform(postJson("/payments/initiate", VALID_BODY).headers(InternalHeaders.asInternalService()))
                .andExpect(status().isForbidden());
    }

    @Test
    void unlistedRoute_isDeniedForAnonymousCaller() throws Exception {
        mockMvc.perform(get("/does-not-exist")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthEndpoint_isOpenToAnonymousCallers() throws Exception {
        // No actuator in this slice, so reaching the dispatcher (404) proves security let the call through
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
    }

    private static MockHttpServletRequestBuilder postJson(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
