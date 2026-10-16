package ua.edu.ukma.springers.voltstore.customer.repositories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.customer.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.customer.entities.CustomerProfile;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Hibernate validate runs on context start, so a passing test also proves the entity matches the schema
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
// Each repository call commits on its own, as in production where every call is its own service transaction
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CustomerProfileRepositoryTest {

    @Autowired
    private CustomerProfileRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTable() {
        jdbc.update("DELETE FROM customer_profiles");
    }

    @Test
    void upsert_insertsANewProfileWithTimestamps() {
        UUID customerId = UUID.randomUUID();

        repository.upsert(customerId, "Ivan", "Petrenko", "+380501234567", null);

        CustomerProfile profile = repository.findById(customerId).orElseThrow();
        assertThat(profile.getFirstName()).isEqualTo("Ivan");
        assertThat(profile.getDefaultAddress()).isNull();
        assertThat(profile.getCreatedAt()).isNotNull();
        assertThat(profile.getUpdatedAt()).isEqualTo(profile.getCreatedAt());
    }

    @Test
    void upsert_updatesAnExistingProfileAndKeepsCreatedAt() {
        UUID customerId = UUID.randomUUID();
        repository.upsert(customerId, "Ivan", "Petrenko", "+380501234567", null);
        Instant createdAt = repository.findById(customerId).orElseThrow().getCreatedAt();

        repository.upsert(customerId, "Olena", "Koval", "+380671112233", "Kyiv, branch 12");

        CustomerProfile profile = repository.findById(customerId).orElseThrow();
        assertThat(profile.getFirstName()).isEqualTo("Olena");
        assertThat(profile.getLastName()).isEqualTo("Koval");
        assertThat(profile.getPhone()).isEqualTo("+380671112233");
        assertThat(profile.getDefaultAddress()).isEqualTo("Kyiv, branch 12");
        assertThat(profile.getCreatedAt()).isEqualTo(createdAt);
        assertThat(profile.getUpdatedAt()).isAfter(createdAt);
        assertThat(repository.count()).isEqualTo(1);
    }
}
