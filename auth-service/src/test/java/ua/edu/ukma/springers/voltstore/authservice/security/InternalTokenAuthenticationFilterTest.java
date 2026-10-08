package ua.edu.ukma.springers.voltstore.authservice.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class InternalTokenAuthenticationFilterTest {
    private static final String TOKEN = "internal-token-internal-token-1234567";

    private final InternalTokenAuthenticationFilter filter = new InternalTokenAuthenticationFilter(TOKEN);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validToken_authenticatesServiceWithServiceAuthority() throws Exception {
        MockFilterChain chain = run(TOKEN);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isEqualTo("internal-service");
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_SERVICE");
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void wrongToken_leavesRequestAnonymousButContinuesChain() throws Exception {
        MockFilterChain chain = run(TOKEN + "x");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void emptyToken_leavesRequestAnonymous() throws Exception {
        run("");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void missingToken_leavesRequestAnonymous() throws Exception {
        run(null);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private MockFilterChain run(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (token != null) {
            request.addHeader("X-Internal-Token", token);
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }
}
