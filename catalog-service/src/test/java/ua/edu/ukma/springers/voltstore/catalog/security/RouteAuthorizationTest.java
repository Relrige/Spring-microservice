package ua.edu.ukma.springers.voltstore.catalog.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ua.edu.ukma.springers.voltstore.catalog.controllers.CategoryController;
import ua.edu.ukma.springers.voltstore.catalog.controllers.ProductController;
import ua.edu.ukma.springers.voltstore.catalog.services.CategoryService;
import ua.edu.ukma.springers.voltstore.catalog.services.ProductService;
import ua.edu.ukma.springers.voltstore.catalog.support.TrustedHeaders;

import java.util.UUID;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 401 / 403 / success for every route, per caller. The services are mocks, so a successful call ends in 2xx
 * (or 200 with an empty body); what matters is that security let it through or stopped it.
 */
@WebMvcTest({CategoryController.class, ProductController.class})
@Import(SecurityConfig.class)
class RouteAuthorizationTest {
    private static final String ID = UUID.randomUUID().toString();
    private static final String VALID_PRODUCT = "{\"title\":\"Phone\",\"description\":\"A phone\",\"categoryId\":\""
            + UUID.randomUUID() + "\",\"basePrice\":10}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CategoryService categoryService;

    @MockitoBean
    private ProductService productService;

    record Route(HttpMethod method, String path, String body) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    static Stream<Route> managerRoutes() {
        return Stream.of(
                new Route(HttpMethod.POST, "/catalog/categories", "{\"name\":\"Laptops\"}"),
                new Route(HttpMethod.PUT, "/catalog/categories/" + ID, "{\"name\":\"Laptops\"}"),
                new Route(HttpMethod.DELETE, "/catalog/categories/" + ID, null),
                new Route(HttpMethod.POST, "/catalog/products", VALID_PRODUCT),
                new Route(HttpMethod.PUT, "/catalog/products/" + ID, VALID_PRODUCT),
                new Route(HttpMethod.PATCH, "/catalog/products/" + ID + "/status", "{\"status\":\"ACTIVE\"}"));
    }

    static Stream<Route> publicRoutes() {
        return Stream.of(
                new Route(HttpMethod.GET, "/catalog/categories", null),
                new Route(HttpMethod.GET, "/catalog/products", null),
                new Route(HttpMethod.GET, "/catalog/products/" + ID, null));
    }

    // --- manager routes ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("managerRoutes")
    void managerRoute_anonymous_returns401(Route route) throws Exception {
        mockMvc.perform(call(route, new HttpHeaders())).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("managerRoutes")
    void managerRoute_otherRolesAndServices_return403(Route route) throws Exception {
        // Roles are not hierarchical: ADMIN is rejected as well
        for (String role : new String[]{"CUSTOMER", "INVENTORY_WORKER", "ADMIN"}) {
            mockMvc.perform(call(route, TrustedHeaders.asUser(role))).andExpect(status().isForbidden());
        }
        mockMvc.perform(call(route, TrustedHeaders.asInternalService())).andExpect(status().isForbidden());
        verifyNoInteractions(categoryService, productService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("managerRoutes")
    void managerRoute_catalogManager_succeeds(Route route) throws Exception {
        mockMvc.perform(call(route, TrustedHeaders.asManager())).andExpect(status().is2xxSuccessful());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("managerRoutes")
    void managerRoute_wrongRoleWithInvalidBody_gets403NotValidationFeedback(Route route) throws Exception {
        if (route.body() == null) {
            return;
        }
        Route invalid = new Route(route.method(), route.path(), "{\"name\":");
        mockMvc.perform(call(invalid, TrustedHeaders.asUser("CUSTOMER"))).andExpect(status().isForbidden());
    }

    // --- public routes ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("publicRoutes")
    void publicRoute_anyCallerIncludingAnonymous_isLetThrough(Route route) throws Exception {
        mockMvc.perform(call(route, new HttpHeaders())).andExpect(status().isOk());
        mockMvc.perform(call(route, TrustedHeaders.asUser("CUSTOMER"))).andExpect(status().isOk());
        mockMvc.perform(call(route, TrustedHeaders.asManager())).andExpect(status().isOk());
    }

    // --- internal route ---

    @ParameterizedTest(name = "{0}")
    @MethodSource("internalRoute")
    void batchGet_anonymous_returns401(Route route) throws Exception {
        mockMvc.perform(call(route, new HttpHeaders())).andExpect(status().isUnauthorized());
        verify(productService, never()).getActiveProduct(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("internalRoute")
    void batchGet_anyUserRole_returns403(Route route) throws Exception {
        for (String role : new String[]{"ADMIN", "CATALOG_MANAGER", "CUSTOMER", "INVENTORY_WORKER"}) {
            mockMvc.perform(call(route, TrustedHeaders.asUser(role))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(productService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("internalRoute")
    void batchGet_validInternalToken_succeeds(Route route) throws Exception {
        mockMvc.perform(call(route, TrustedHeaders.asInternalService())).andExpect(status().isOk());
    }

    static Stream<Route> internalRoute() {
        return Stream.of(new Route(HttpMethod.GET, "/catalog/products/batch-get?ids=" + ID, null));
    }

    private static MockHttpServletRequestBuilder call(Route route, HttpHeaders headers) {
        MockHttpServletRequestBuilder builder = request(route.method(), route.path()).headers(headers);
        if (route.body() != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(route.body());
        }
        return builder;
    }
}
