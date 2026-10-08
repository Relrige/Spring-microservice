package ua.edu.ukma.springers.voltstore.authservice.repositories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import ua.edu.ukma.springers.voltstore.authservice.TestcontainersConfiguration;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserEntity;
import ua.edu.ukma.springers.voltstore.authservice.entities.UserRole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(TestcontainersConfiguration.class)
class UserRepositoryTest {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUsersTable() {
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void save_persistsAllFieldsAndGeneratesId() {
        UserEntity saved = userRepository.saveAndFlush(user("alice@example.com", UserRole.CUSTOMER));

        assertThat(saved.getId()).isNotNull();

        UserEntity found = userRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getEmail()).isEqualTo("alice@example.com");
        assertThat(found.getPasswordHash()).isEqualTo("hash");
        assertThat(found.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void save_storesRoleAsString(UserRole role) {
        UserEntity saved = userRepository.saveAndFlush(user("role-" + role + "@example.com", role));

        String stored = jdbcTemplate.queryForObject(
                "SELECT user_role FROM users WHERE id = ?", String.class, saved.getId());

        assertThat(stored).isEqualTo(role.name());
    }

    @Test
    void findByEmail_returnsUserWhenPresent() {
        userRepository.saveAndFlush(user("bob@example.com", UserRole.CUSTOMER));

        assertThat(userRepository.findByEmail("bob@example.com"))
                .isPresent()
                .get()
                .extracting(UserEntity::getEmail)
                .isEqualTo("bob@example.com");
    }

    @Test
    void findByEmail_returnsEmptyWhenAbsent() {
        assertThat(userRepository.findByEmail("nobody@example.com")).isEmpty();
    }

    @Test
    void existsByEmail_reflectsPresence() {
        userRepository.saveAndFlush(user("carol@example.com", UserRole.CUSTOMER));

        assertThat(userRepository.existsByEmail("carol@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("dave@example.com")).isFalse();
    }

    @Test
    void save_duplicateEmail_violatesUniqueConstraint() {
        userRepository.saveAndFlush(user("eve@example.com", UserRole.CUSTOMER));

        assertThatThrownBy(() -> userRepository.saveAndFlush(user("eve@example.com", UserRole.CATALOG_MANAGER)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static UserEntity user(String email, UserRole role) {
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.setRole(role);
        return user;
    }
}
