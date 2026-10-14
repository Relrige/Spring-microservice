package ua.edu.ukma.springers.voltstore.catalog.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import ua.edu.ukma.springers.voltstore.catalog.services.CategoryService;
import ua.edu.ukma.springers.voltstore.catalog.services.ProductService;
import ua.edu.ukma.springers.voltstore.catalog.support.TrustedHeaders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Guards the fail-closed default: every route of this service must have an explicit rule in {@link SecurityConfig}.
 * <p>
 * Adapted from auth-service: there, ADMIN is the most privileged caller, so "403 for ADMIN" means "no rule". Here
 * roles are not hierarchical (ADMIN is denied on manager routes on purpose), so a route counts as unruled only if
 * <em>every</em> caller (each user role) is denied. The internal-only route is excluded and checked with a
 * SERVICE caller instead.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class RouteSecurityRulesTest {
    private static final List<String> USER_ROLES = List.of("CUSTOMER", "CATALOG_MANAGER", "INVENTORY_WORKER", "ADMIN");
    private static final Set<String> INTERNAL_ROUTES = Set.of("GET /catalog/products/batch-get");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    private CategoryService categoryService;

    @MockitoBean
    private ProductService productService;

    @Test
    void everyApplicationRouteHasAnExplicitRule() throws Exception {
        List<String> routes = new ArrayList<>();
        List<String> deniedRoutes = new ArrayList<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (!entry.getValue().getBeanType().isAnnotationPresent(RestController.class)
                    || !entry.getValue().getBeanType().getPackageName().startsWith("ua.edu.ukma")) {
                continue;
            }
            for (String path : entry.getKey().getPathPatternsCondition().getPatternValues()) {
                for (var method : entry.getKey().getMethodsCondition().getMethods()) {
                    String route = method + " " + path;
                    routes.add(route);
                    if (INTERNAL_ROUTES.contains(route)) {
                        if (status(method.name(), path, TrustedHeaders.asInternalService()) == 403) {
                            deniedRoutes.add(route);
                        }
                        continue;
                    }
                    boolean someoneAllowed = false;
                    for (String role : USER_ROLES) {
                        someoneAllowed |= status(method.name(), path, TrustedHeaders.asUser(role)) != 403;
                    }
                    if (!someoneAllowed) {
                        deniedRoutes.add(route);
                    }
                }
            }
        }

        assertThat(routes).as("application routes discovered").isNotEmpty().containsAll(INTERNAL_ROUTES);
        assertThat(deniedRoutes).as("routes without an explicit security rule").isEmpty();
    }

    private int status(String method, String pathPattern, HttpHeaders headers) throws Exception {
        // Fill path variables such as {id} with a UUID
        String path = pathPattern.replaceAll("\\{[^/]+}", UUID.randomUUID().toString());
        return mockMvc.perform(request(HttpMethod.valueOf(method), path).headers(headers)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus();
    }
}
