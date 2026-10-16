package ua.edu.ukma.springers.voltstore.customer.security;

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
import ua.edu.ukma.springers.voltstore.customer.services.CustomerProfileService;
import ua.edu.ukma.springers.voltstore.customer.support.TrustedHeaders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Guards the fail-closed default: every route of this service must have an explicit rule in {@link SecurityConfig}.
 * A route is considered covered if at least one user role gets past the authorization step; a route denied (403)
 * for all of them has no rule, because the chain ends with {@code denyAll()}.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class RouteSecurityRulesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    private CustomerProfileService customerProfileService;

    @Test
    void everyApplicationRouteHasAnExplicitRule() throws Exception {
        List<String> routes = new ArrayList<>();
        List<String> deniedRoutes = new ArrayList<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (!entry.getValue().getBeanType().isAnnotationPresent(RestController.class)
                    || !entry.getValue().getBeanType().getPackageName().startsWith("ua.edu.ukma")) {
                continue;
            }
            for (String pattern : entry.getKey().getPathPatternsCondition().getPatternValues()) {
                // Fill path variables with a concrete value so the pattern can be requested
                String path = pattern.replaceAll("\\{[^/}]+}", UUID.randomUUID().toString());
                for (var method : entry.getKey().getMethodsCondition().getMethods()) {
                    routes.add(method + " " + pattern);
                    boolean reachable = false;
                    for (HttpHeaders caller : callers()) {
                        int status = mockMvc.perform(request(HttpMethod.valueOf(method.name()), path)
                                        .headers(caller)
                                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                                .andReturn().getResponse().getStatus();
                        reachable |= status != 403;
                    }
                    if (!reachable) {
                        deniedRoutes.add(method + " " + pattern);
                    }
                }
            }
        }

        assertThat(routes).as("application routes discovered").isNotEmpty();
        assertThat(deniedRoutes).as("routes without an explicit security rule").isEmpty();
    }

    private static List<HttpHeaders> callers() {
        List<HttpHeaders> callers = new ArrayList<>();
        for (UserRole role : UserRole.values()) {
            callers.add(TrustedHeaders.asUser(role.name()));
        }
        return callers;
    }
}
