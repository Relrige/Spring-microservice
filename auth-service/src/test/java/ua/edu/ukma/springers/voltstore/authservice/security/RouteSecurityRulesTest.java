package ua.edu.ukma.springers.voltstore.authservice.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import ua.edu.ukma.springers.voltstore.authservice.services.AuthService;
import ua.edu.ukma.springers.voltstore.authservice.services.UserService;
import ua.edu.ukma.springers.voltstore.authservice.support.TrustedHeaders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Guards the fail-closed default: every route of this service must have an explicit rule in {@link SecurityConfig}.
 * An administrator is the most privileged caller here, so if even an administrator is denied (403) on a route,
 * that route has no rule (the chain ends with {@code denyAll()}).
 * <p>
 * If a route is ever meant to be unreachable for administrators (e.g. internal-only routes), exclude it here
 * explicitly and test it with the appropriate caller.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class RouteSecurityRulesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AuthService authService;

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
                    routes.add(method + " " + path);
                    int status = mockMvc.perform(request(HttpMethod.valueOf(method.name()), path)
                                    .headers(TrustedHeaders.asUser("ADMIN"))
                                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                            .andReturn().getResponse().getStatus();
                    if (status == 403) {
                        deniedRoutes.add(method + " " + path);
                    }
                }
            }
        }

        assertThat(routes).as("application routes discovered").isNotEmpty();
        assertThat(deniedRoutes).as("routes without an explicit security rule").isEmpty();
    }
}
