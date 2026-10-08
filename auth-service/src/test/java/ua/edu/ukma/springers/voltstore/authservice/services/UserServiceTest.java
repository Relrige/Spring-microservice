package ua.edu.ukma.springers.voltstore.authservice.services;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import ua.edu.ukma.springers.voltstore.authservice.dto.CreateNonCustomerUserRequest;
import ua.edu.ukma.springers.voltstore.authservice.dto.RegisterUserRequest;
import ua.edu.ukma.springers.voltstore.authservice.dto.RegisterUserResponse;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserEntity;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserRole;
import ua.edu.ukma.springers.voltstore.authservice.exceptions.EmailNotUniqueException;
import ua.edu.ukma.springers.voltstore.authservice.exceptions.InvalidUserRoleException;
import ua.edu.ukma.springers.voltstore.authservice.repositories.UserRepository;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepo;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    @Test
    void registerUser_hashesPasswordAndCreatesCustomer() {
        UUID id = UUID.randomUUID();
        when(userRepo.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret")).thenReturn("hashed-secret");
        when(userRepo.saveAndFlush(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity entity = invocation.getArgument(0);
            entity.setId(id);
            return entity;
        });

        RegisterUserResponse response = userService.registerUser(request("alice@example.com", "secret"));

        assertThat(response.getUserId()).isEqualTo(id);
        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepo).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed-secret").isNotEqualTo("secret");
        assertThat(saved.getValue().getRole()).isEqualTo(UserRole.CUSTOMER);
    }

    @Test
    void registerUser_normalizesEmail() {
        when(passwordEncoder.encode(any())).thenReturn("hash");

        userService.registerUser(request("  Alice@Example.COM ", "secret"));

        verify(userRepo).existsByEmail("alice@example.com");
        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepo).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void registerUser_existingEmail_throwsAndDoesNotSave() {
        when(userRepo.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.registerUser(request("Alice@example.com", "secret")))
                .isInstanceOf(EmailNotUniqueException.class)
                .hasMessage("Email already in use");

        verify(userRepo, never()).saveAndFlush(any());
    }

    @Test
    void registerUser_emailUniqueConstraintViolation_mapsToEmailNotUnique() {
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(userRepo.saveAndFlush(any())).thenThrow(constraintViolation("users_email_key"));

        assertThatThrownBy(() -> userService.registerUser(request("alice@example.com", "secret")))
                .isInstanceOf(EmailNotUniqueException.class);
    }

    @Test
    void registerUser_otherIntegrityViolation_isNotMappedToEmailNotUnique() {
        DataIntegrityViolationException other = constraintViolation("users_user_role_check");
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(userRepo.saveAndFlush(any())).thenThrow(other);

        assertThatThrownBy(() -> userService.registerUser(request("alice@example.com", "secret")))
                .isSameAs(other);
    }

    @Test
    void createNonCustomerUser_savesRequestedRole() {
        when(passwordEncoder.encode("secret")).thenReturn("hashed-secret");

        userService.createNonCustomerUser(nonCustomerRequest("manager@example.com", UserRole.CATALOG_MANAGER));

        ArgumentCaptor<UserEntity> saved = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepo).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(UserRole.CATALOG_MANAGER);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed-secret");
    }

    @Test
    void createNonCustomerUser_customerRole_isRejectedAndNothingSaved() {
        assertThatThrownBy(() -> userService.createNonCustomerUser(nonCustomerRequest("c@example.com", UserRole.CUSTOMER)))
                .isInstanceOf(InvalidUserRoleException.class);

        verify(userRepo, never()).saveAndFlush(any());
    }

    @Test
    void createNonCustomerUser_existingEmail_throwsEmailNotUnique() {
        when(userRepo.existsByEmail("manager@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.createNonCustomerUser(nonCustomerRequest("manager@example.com", UserRole.ADMIN)))
                .isInstanceOf(EmailNotUniqueException.class);
    }

    private static CreateNonCustomerUserRequest nonCustomerRequest(String email, UserRole role) {
        CreateNonCustomerUserRequest request = new CreateNonCustomerUserRequest();
        request.setEmail(email);
        request.setPassword("secret");
        request.setRole(role);
        return request;
    }

    private static DataIntegrityViolationException constraintViolation(String constraintName) {
        return new DataIntegrityViolationException("violation",
                new ConstraintViolationException("violation", new SQLException("violation"), constraintName));
    }

    private static RegisterUserRequest request(String email, String password) {
        RegisterUserRequest request = new RegisterUserRequest();
        request.setEmail(email);
        request.setPassword(password);
        return request;
    }
}
