package ua.edu.ukma.springers.voltstore.authservice.security;

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
import ua.edu.ukma.springers.voltstore.authservice.controllers.UserController;
import ua.edu.ukma.springers.voltstore.authservice.dto.RegisterUserResponse;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserRole;
import ua.edu.ukma.springers.voltstore.authservice.services.UserService;
import ua.edu.ukma.springers.voltstore.authservice.support.TrustedHeaders;

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
 * deny by default). The caller identity is observed from inside the public registration endpoint, whose mocked
 * service records the {@link Authentication} present during the request.
 */
@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class SecurityConfigTest {
    private static final String REGISTER_BODY = "{\"email\":\"alice@example.com\",\"password\":\"secret1\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    private final AtomicReference<Authentication> seenAuthentication = new AtomicReference<>();

    @BeforeEach
    void recordAuthenticationSeenByService() {
        when(userService.registerUser(any())).thenAnswer(invocation -> {
            seenAuthentication.set(SecurityContextHolder.getContext().getAuthentication());
            return new RegisterUserResponse(UUID.randomUUID());
        });
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousCaller_getsFixed401ProblemOnProtectedRoute() throws Exception {
        mockMvc.perform(post("/user/non-customer").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Authentication required"))
                .andExpect(jsonPath("$.service").value("auth-service"))
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
    void publicRouteWithWrongHttpMethod_isNotPubliclyOpen() throws Exception {
        mockMvc.perform(get("/user/register")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicRoute_worksWithoutCredentialsAndWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/user/register").contentType(MediaType.APPLICATION_JSON).content(REGISTER_BODY))
                .andExpect(status().isCreated());
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

        register(headers);

        assertThat(seenAuthentication.get().getPrincipal()).isEqualTo(
                new AuthenticatedUser(id, UserRole.INVENTORY_WORKER));
        assertThat(authorities()).containsExactly("ROLE_INVENTORY_WORKER");
    }

    @Test
    void malformedUserHeaders_areTreatedAsAnonymous() throws Exception {
        register(TrustedHeaders.asUser("SUPERUSER"));

        assertThat(authorities()).containsExactly("ROLE_ANONYMOUS");
    }

    @Test
    void validInternalToken_authenticatesAsService() throws Exception {
        register(TrustedHeaders.asInternalService());

        assertThat(authorities()).containsExactly("ROLE_SERVICE");
    }

    @Test
    void wrongInternalToken_isAnonymous() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Token", "wrong-token-wrong-token-wrong-token-1");

        register(headers);

        assertThat(authorities()).containsExactly("ROLE_ANONYMOUS");
    }

    @Test
    void validInternalToken_takesPrecedenceOverUserHeaders() throws Exception {
        HttpHeaders headers = TrustedHeaders.asUser("ADMIN");
        headers.addAll(TrustedHeaders.asInternalService());

        register(headers);

        assertThat(authorities()).containsExactly("ROLE_SERVICE");
    }

    @Test
    void wrongInternalToken_doesNotBlockValidUserHeaders() throws Exception {
        HttpHeaders headers = TrustedHeaders.asUser("CUSTOMER");
        headers.add("X-Internal-Token", "wrong-token-wrong-token-wrong-token-1");

        register(headers);

        assertThat(authorities()).containsExactly("ROLE_CUSTOMER");
    }

    private void register(HttpHeaders headers) throws Exception {
        mockMvc.perform(post("/user/register").headers(headers)
                        .contentType(MediaType.APPLICATION_JSON).content(REGISTER_BODY))
                .andExpect(status().isCreated());
    }

    private List<String> authorities() {
        return seenAuthentication.get().getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
    }
}
