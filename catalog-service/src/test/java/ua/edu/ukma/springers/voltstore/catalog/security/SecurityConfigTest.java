package ua.edu.ukma.springers.voltstore.catalog.security;

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
import ua.edu.ukma.springers.voltstore.catalog.controllers.CategoryController;
import ua.edu.ukma.springers.voltstore.catalog.dto.PageResponse;
import ua.edu.ukma.springers.voltstore.catalog.services.CategoryService;
import ua.edu.ukma.springers.voltstore.catalog.support.TrustedHeaders;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the generic parts of the security chain (authentication from headers, internal token, public routes,
 * deny by default). The caller identity is observed from inside the public category listing, whose mocked
 * service records the {@link Authentication} present during the request.
 */
@WebMvcTest(CategoryController.class)
@Import(SecurityConfig.class)
class SecurityConfigTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CategoryService categoryService;

    private final AtomicReference<Authentication> seenAuthentication = new AtomicReference<>();

    @BeforeEach
    void recordAuthenticationSeenByService() {
        when(categoryService.listCategories(any())).thenAnswer(invocation -> {
            seenAuthentication.set(SecurityContextHolder.getContext().getAuthentication());
            return new PageResponse<>(List.of(), 0, 0);
        });
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousCaller_getsFixed401ProblemOnProtectedRoute() throws Exception {
        mockMvc.perform(post("/catalog/categories").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Authentication required"))
                .andExpect(jsonPath("$.service").value("catalog-service"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void wrongRole_getsFixed403Problem() throws Exception {
        mockMvc.perform(post("/catalog/categories").headers(TrustedHeaders.asUser("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://voltstore.com/errors/forbidden"))
                .andExpect(jsonPath("$.detail").value("Access denied"))
                .andExpect(jsonPath("$.service").value("catalog-service"));
    }

    @Test
    void unlistedRoute_isDeniedEvenForEveryRole() throws Exception {
        for (String role : new String[]{"ADMIN", "CATALOG_MANAGER", "CUSTOMER", "INVENTORY_WORKER"}) {
            mockMvc.perform(get("/does-not-exist").headers(TrustedHeaders.asUser(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value("Access denied"));
        }
        mockMvc.perform(get("/does-not-exist").headers(TrustedHeaders.asInternalService()))
                .andExpect(status().isForbidden());
    }

    @Test
    void unlistedRoute_isDeniedForAnonymousCaller() throws Exception {
        mockMvc.perform(get("/does-not-exist")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicRouteWithWrongHttpMethod_isNotPubliclyOpen() throws Exception {
        mockMvc.perform(post("/catalog/categories")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicRoute_worksWithoutCredentials() throws Exception {
        mockMvc.perform(get("/catalog/categories")).andExpect(status().isOk());
    }

    @Test
    void healthEndpoint_isOpenToAnonymousCallers() throws Exception {
        // No actuator in this slice, so reaching the dispatcher (404) proves security let the call through
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isNotFound());
    }

    @Test
    void userHeaders_authenticateUserWithRoleAuthorityAndPrincipal() throws Exception {
        UUID id = UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", id.toString());
        headers.add("X-User-Role", "CATALOG_MANAGER");

        list(headers);

        assertThat(seenAuthentication.get().getPrincipal()).isEqualTo(
                new AuthenticatedUser(id, UserRole.CATALOG_MANAGER));
        assertThat(authorities()).containsExactly("ROLE_CATALOG_MANAGER");
    }

    @Test
    void malformedUserHeaders_areTreatedAsAnonymous() throws Exception {
        list(TrustedHeaders.asUser("SUPERUSER"));

        assertThat(authorities()).containsExactly("ROLE_ANONYMOUS");
    }

    @Test
    void validInternalToken_authenticatesAsService() throws Exception {
        list(TrustedHeaders.asInternalService());

        assertThat(authorities()).containsExactly("ROLE_SERVICE");
    }

    @Test
    void wrongInternalToken_isAnonymous() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Token", "wrong-token-wrong-token-wrong-token-1");

        list(headers);

        assertThat(authorities()).containsExactly("ROLE_ANONYMOUS");
    }

    @Test
    void validInternalToken_takesPrecedenceOverUserHeaders() throws Exception {
        HttpHeaders headers = TrustedHeaders.asUser("ADMIN");
        headers.addAll(TrustedHeaders.asInternalService());

        list(headers);

        assertThat(authorities()).containsExactly("ROLE_SERVICE");
    }

    @Test
    void wrongInternalToken_doesNotBlockValidUserHeaders() throws Exception {
        HttpHeaders headers = TrustedHeaders.asUser("CUSTOMER");
        headers.add("X-Internal-Token", "wrong-token-wrong-token-wrong-token-1");

        list(headers);

        assertThat(authorities()).containsExactly("ROLE_CUSTOMER");
    }

    private void list(HttpHeaders headers) throws Exception {
        mockMvc.perform(get("/catalog/categories").headers(headers)).andExpect(status().isOk());
    }

    private List<String> authorities() {
        return seenAuthentication.get().getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
    }
}
