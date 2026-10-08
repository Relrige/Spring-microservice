package ua.edu.ukma.springers.voltstore.authservice.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import ua.edu.ukma.springers.voltstore.authservice.dto.LoginRequest;
import ua.edu.ukma.springers.voltstore.authservice.dto.LoginResponse;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserEntity;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserRole;
import ua.edu.ukma.springers.voltstore.authservice.exceptions.InvalidCredentialsException;
import ua.edu.ukma.springers.voltstore.authservice.repositories.UserRepository;
import ua.edu.ukma.springers.voltstore.authservice.security.TokenService;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepo;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenService tokenService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(any())).thenReturn("dummy-hash");
        authService = new AuthService(userRepo, passwordEncoder, tokenService);
    }

    @Test
    void login_correctCredentials_returnsToken() {
        UserEntity user = user();
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret1", "stored-hash")).thenReturn(true);
        when(tokenService.issueToken(user)).thenReturn("jwt-token");

        LoginResponse response = authService.login(request("alice@example.com", "secret1"));

        assertThat(response.getToken()).isEqualTo("jwt-token");
    }

    @Test
    void login_normalizesEmail() {
        UserEntity user = user();
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret1", "stored-hash")).thenReturn(true);
        when(tokenService.issueToken(user)).thenReturn("jwt-token");

        authService.login(request("  Alice@Example.COM ", "secret1"));

        verify(userRepo).findByEmail("alice@example.com");
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentials() {
        when(userRepo.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(passwordEncoder.matches("wrong", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request("alice@example.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(tokenService, never()).issueToken(any());
    }

    @Test
    void login_unknownEmail_throwsInvalidCredentialsAndStillRunsPasswordCheck() {
        when(userRepo.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.matches("secret1", "dummy-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request("nobody@example.com", "secret1")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid credentials");

        // The hash comparison must run even for unknown users to equalize response timing
        verify(passwordEncoder).matches(eq("secret1"), eq("dummy-hash"));
        verify(tokenService, never()).issueToken(any());
    }

    private static UserEntity user() {
        return UserEntity.builder().id(UUID.randomUUID()).email("alice@example.com")
                .passwordHash("stored-hash").role(UserRole.CUSTOMER).build();
    }

    private static LoginRequest request(String email, String password) {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword(password);
        return request;
    }
}
