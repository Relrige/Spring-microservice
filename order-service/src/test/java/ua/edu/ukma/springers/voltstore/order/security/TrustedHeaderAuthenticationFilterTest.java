package ua.edu.ukma.springers.voltstore.order.security;

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
        assertThat(authentication.getPrincipal()).isEqualTo(new AuthenticatedUser(id, UserRole.CATALOG_MANAGER, null));
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CATALOG_MANAGER");
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void emailHeader_isCarriedOnThePrincipal() throws Exception {
        UUID id = UUID.randomUUID();
        MockHttpServletRequest request = request(id.toString(), "CUSTOMER");
        request.addHeader("X-User-Email", " customer@example.com ");

        run(request);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo(new AuthenticatedUser(id, UserRole.CUSTOMER, "customer@example.com"));
    }

    @Test
    void implausibleEmail_isDropped_butTheUserIsStillAuthenticated() throws Exception {
        for (String email : new String[]{"", "   ", "no-at-sign", "a@" + "x".repeat(300)}) {
            MockHttpServletRequest request = request(UUID.randomUUID().toString(), "CUSTOMER");
            request.addHeader("X-User-Email", email);

            run(request);

            AuthenticatedUser user = (AuthenticatedUser) SecurityContextHolder.getContext().getAuthentication()
                    .getPrincipal();
            assertThat(user.email()).isNull();
            assertThat(user.role()).isEqualTo(UserRole.CUSTOMER);
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void emailAlone_doesNotAuthenticate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Email", "customer@example.com");

        run(request);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void principalToString_doesNotContainTheEmail() {
        AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UserRole.CUSTOMER, "customer@example.com");

        assertThat(user.toString()).doesNotContain("customer@example.com").contains("CUSTOMER");
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
