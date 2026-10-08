package ua.edu.ukma.springers.voltstore.authservice.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserRole;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedHeaderAuthenticationFilterTest {
    private final TrustedHeaderAuthenticationFilter filter = new TrustedHeaderAuthenticationFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validHeaders_authenticateUserWithRoleAuthority() throws Exception {
        UUID id = UUID.randomUUID();

        MockFilterChain chain = run(request(id.toString(), "CATALOG_MANAGER"));

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isEqualTo(new AuthenticatedUser(id, UserRole.CATALOG_MANAGER));
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CATALOG_MANAGER");
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void missingHeaders_leaveRequestAnonymousButContinueChain() throws Exception {
        MockFilterChain chain = run(new MockHttpServletRequest());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void onlyOneHeader_leavesRequestAnonymous() throws Exception {
        run(request(UUID.randomUUID().toString(), null));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        run(request(null, "ADMIN"));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void malformedUserId_leavesRequestAnonymous() throws Exception {
        run(request("not-a-uuid", "ADMIN"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void unknownRole_leavesRequestAnonymous() throws Exception {
        run(request(UUID.randomUUID().toString(), "SUPERUSER"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void roleIsCaseSensitive() throws Exception {
        run(request(UUID.randomUUID().toString(), "admin"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void alreadyAuthenticatedCaller_isNotOverriddenByUserHeaders() throws Exception {
        Authentication service = new PreAuthenticatedAuthenticationToken(
                "internal-service", null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
        SecurityContextHolder.getContext().setAuthentication(service);

        run(request(UUID.randomUUID().toString(), "ADMIN"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(service);
    }

    private MockFilterChain run(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    private static MockHttpServletRequest request(String userId, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (userId != null) {
            request.addHeader("X-User-Id", userId);
        }
        if (role != null) {
            request.addHeader("X-User-Role", role);
        }
        return request;
    }
}
