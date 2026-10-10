package ua.edu.ukma.springers.voltstore.inventory.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.edu.ukma.springers.voltstore.inventory.controllers.StockController;
import ua.edu.ukma.springers.voltstore.inventory.services.StockService;
import ua.edu.ukma.springers.voltstore.inventory.support.TrustedHeaders;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the generic parts of the security chain (authentication from headers, internal token, public routes,
 * deny by default). The caller identity is observed from inside the adjust endpoint, whose mocked service records
 * the {@link Authentication} present during the request.
 */
@WebMvcTest(StockController.class)
@Import(SecurityConfig.class)
class SecurityConfigTest {
    private static final String URL = "/inventory/stock/" + UUID.randomUUID() + "/adjust";
    private static final String BODY = "{\"delta\":5}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockService stockService;

    private final AtomicReference<Authentication> seenAuthentication = new AtomicReference<>();

    @BeforeEach
    void recordAuthenticationSeenByService() {
        when(stockService.adjustStock(any(), anyInt())).thenAnswer(invocation -> {
            seenAuthentication.set(SecurityContextHolder.getContext().getAuthentication());
            return 5;
        });
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousCaller_getsFixed401ProblemOnProtectedRoute() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Authentication required"))
                .andExpect(jsonPath("$.service").value("inventory-service"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void unlistedRoute_isDeniedEvenForAdmin() throws Exception {
        mockMvc.perform(get("/does-not-exist").headers(TrustedHeaders.asUser("ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Access denied"));
    }

    @Test
    void unlistedRoute_isDeniedForAnonymousCaller() throws Exception {
        mockMvc.perform(get("/does-not-exist")).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedRouteWithWrongHttpMethod_isDenied() throws Exception {
        mockMvc.perform(get(URL).headers(TrustedHeaders.asUser("INVENTORY_WORKER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthEndpoint_isOpenToAnonymousCallers() throws Exception {
        // No actuator in this slice, so reaching the dispatcher (404) proves security let the call through
        mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
    }

    @Test
    void userHeaders_authenticateUserWithRoleAuthorityAndPrincipal() throws Exception {
        UUID id = UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", id.toString());
        headers.add("X-User-Role", "INVENTORY_WORKER");

        adjust(headers);

        assertThat(seenAuthentication.get().getPrincipal()).isEqualTo(
                new AuthenticatedUser(id, UserRole.INVENTORY_WORKER));
        assertThat(authorities()).containsExactly("ROLE_INVENTORY_WORKER");
    }

    @Test
    void malformedUserHeaders_areTreatedAsAnonymous() throws Exception {
        mockMvc.perform(post(URL).headers(TrustedHeaders.asUser("SUPERUSER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validInternalToken_authenticatesAsServiceAndIsNotAWorker() throws Exception {
        mockMvc.perform(post(URL).headers(TrustedHeaders.asInternalService())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongInternalToken_doesNotBlockValidUserHeaders() throws Exception {
        HttpHeaders headers = TrustedHeaders.asUser("INVENTORY_WORKER");
        headers.add("X-Internal-Token", "wrong-token-wrong-token-wrong-token-1");

        adjust(headers);

        assertThat(authorities()).containsExactly("ROLE_INVENTORY_WORKER");
    }

    @Test
    void validInternalToken_takesPrecedenceOverUserHeaders() throws Exception {
        HttpHeaders headers = TrustedHeaders.asUser("INVENTORY_WORKER");
        headers.addAll(TrustedHeaders.asInternalService());

        mockMvc.perform(post(URL).headers(headers).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    private void adjust(HttpHeaders headers) throws Exception {
        mockMvc.perform(post(URL).headers(headers).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }

    private List<String> authorities() {
        return seenAuthentication.get().getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
    }
}
