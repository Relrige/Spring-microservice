package ua.edu.ukma.springers.voltstore.order.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ua.edu.ukma.springers.voltstore.order.config.CheckoutConfig;
import ua.edu.ukma.springers.voltstore.order.controllers.OrderController;
import ua.edu.ukma.springers.voltstore.order.dto.PlacedOrderResponse;
import ua.edu.ukma.springers.voltstore.order.entities.OrderStatus;
import ua.edu.ukma.springers.voltstore.order.services.CheckoutCheckService;
import ua.edu.ukma.springers.voltstore.order.services.OrderAccess;
import ua.edu.ukma.springers.voltstore.order.services.OrderPlacementService;
import ua.edu.ukma.springers.voltstore.order.support.OrderRoutesProbeController;
import ua.edu.ukma.springers.voltstore.order.support.TrustedHeaders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the security chain against the real {@link OrderController} and stand-ins of the routes not written yet
 * ({@link OrderRoutesProbeController}), with the services mocked: only
 * customers may use the customer routes, only workers the worker routes, and nothing else is reachable.
 */
@WebMvcTest(controllers = {OrderRoutesProbeController.class, OrderController.class})
@Import({SecurityConfig.class, CheckoutConfig.class, OrderRoutesProbeController.class})
class SecurityConfigTest {
    private static final String ORDER = "/orders/" + UUID.randomUUID();
    // Valid for both real POST endpoints (the soft check ignores deliveryDetails)
    private static final String BODY = """
            {"items":[{"productId":"%s","quantity":1}],
             "deliveryDetails":{"name":"Ivan Petrenko","phone":"+380501234567","address":"Kyiv, branch 12",
                                "carrier":"NOVA_POSHTA"}}""".formatted(UUID.randomUUID());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderAccess orderAccess;

    @MockitoBean
    private CheckoutCheckService checkoutCheck;

    @MockitoBean
    private OrderPlacementService placement;

    @BeforeEach
    void placementAnswers() {
        when(placement.place(any(), any())).thenReturn(new PlacedOrderResponse(UUID.randomUUID(),
                OrderStatus.PENDING_PAYMENT, new BigDecimal("100.00"), "UAH", Instant.now()));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "POST,   /orders/validate",
            "POST,   /orders",
            "POST,   /orders/{id}/pay",
            "GET,    /orders",
            "DELETE, /orders/{id}"
    })
    void customerRoutes_allowCustomers_only(String method, String path) throws Exception {
        String url = path.replace("{id}", UUID.randomUUID().toString());

        mockMvc.perform(send(method, url).headers(TrustedHeaders.asUser("CUSTOMER")))
                .andExpect(status().is2xxSuccessful());
        for (String role : new String[]{"ADMIN", "CATALOG_MANAGER", "INVENTORY_WORKER"}) {
            mockMvc.perform(send(method, url).headers(TrustedHeaders.asUser(role))).andExpect(status().isForbidden());
        }
        mockMvc.perform(send(method, url)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET,  /orders/fulfillment-queue",
            "POST, /orders/{id}/ship"
    })
    void workerRoutes_allowInventoryWorkers_only(String method, String path) throws Exception {
        String url = path.replace("{id}", UUID.randomUUID().toString());

        mockMvc.perform(send(method, url).headers(TrustedHeaders.asUser("INVENTORY_WORKER")))
                .andExpect(status().isOk());
        for (String role : new String[]{"ADMIN", "CATALOG_MANAGER", "CUSTOMER"}) {
            mockMvc.perform(send(method, url).headers(TrustedHeaders.asUser(role))).andExpect(status().isForbidden());
        }
        mockMvc.perform(send(method, url)).andExpect(status().isUnauthorized());
    }

    @Test
    void orderDetails_isForCustomersOnly() throws Exception {
        // A customer reaching the route is covered with a real database in OrderAccessIntegrationTest
        for (String role : new String[]{"ADMIN", "CATALOG_MANAGER", "INVENTORY_WORKER"}) {
            mockMvc.perform(get(ORDER).headers(TrustedHeaders.asUser(role))).andExpect(status().isForbidden());
        }
        mockMvc.perform(get(ORDER)).andExpect(status().isUnauthorized());
    }

    @Test
    void fulfillmentQueue_isNotMistakenForAnOrderId() throws Exception {
        // GET /orders/{orderId} would also match the path; the worker rule must win
        mockMvc.perform(get("/orders/fulfillment-queue").headers(TrustedHeaders.asUser("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCaller_getsTheSharedProblemShape() throws Exception {
        mockMvc.perform(get("/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Authentication required"))
                .andExpect(jsonPath("$.service").value("order-service"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void roleIsCheckedBeforeTheBodyIsValidated() throws Exception {
        // 403, not 400: a worker never gets validation feedback on a customer route
        mockMvc.perform(post("/orders").headers(TrustedHeaders.asUser("INVENTORY_WORKER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/orders").headers(TrustedHeaders.asUser("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customerIdentityAndEmail_reachTheService() throws Exception {
        UUID customerId = UUID.randomUUID();

        mockMvc.perform(post("/orders").headers(TrustedHeaders.asUser(customerId, "CUSTOMER", "ivan@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());

        ArgumentCaptor<AuthenticatedUser> caller = ArgumentCaptor.forClass(AuthenticatedUser.class);
        verify(placement).place(caller.capture(), any());
        assertThat(caller.getValue().id()).isEqualTo(customerId);
        assertThat(caller.getValue().role()).isEqualTo(UserRole.CUSTOMER);
        assertThat(caller.getValue().email()).isEqualTo("ivan@example.com");
    }

    @Test
    void internalTokenGrantsNothing() throws Exception {
        // Order has no internal routes and no internal-token filter
        mockMvc.perform(get("/orders").header("X-Internal-Token", "internal-token-internal-token-1234567"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/orders", "/orders/customer/" + "00000000-0000-0000-0000-000000000000"})
    void draftAndUnlistedRoutes_areDenied(String path) throws Exception {
        mockMvc.perform(get(path).headers(TrustedHeaders.asUser("CUSTOMER"))).andExpect(status().isForbidden());
        mockMvc.perform(get(path).headers(TrustedHeaders.asUser("ADMIN"))).andExpect(status().isForbidden());
    }

    @Test
    void wrongMethodOnACustomerPath_isDenied() throws Exception {
        mockMvc.perform(request(HttpMethod.PUT, ORDER).headers(TrustedHeaders.asUser("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthEndpoint_isOpen() throws Exception {
        // No actuator in this slice, so reaching the dispatcher (404) proves security let the call through
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
    }

    private static MockHttpServletRequestBuilder send(String method, String url) {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), url);
        return method.equals("POST") ? builder.contentType(MediaType.APPLICATION_JSON).content(BODY) : builder;
    }
}
