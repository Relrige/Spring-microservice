package ua.edu.ukma.springers.voltstore.catalog.repositories;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Testcontainers;
import ua.edu.ukma.springers.voltstore.catalog.PostgresContainerConfiguration;
import ua.edu.ukma.springers.voltstore.catalog.entities.Category;
import ua.edu.ukma.springers.voltstore.catalog.utils.ConstraintViolations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PostgresContainerConfiguration.class)
class CategoryRepositoryTest {
    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    void save_persistsNameAndGeneratesId() {
        Category saved = categoryRepository.saveAndFlush(new Category("Laptops"));

        assertThat(saved.getId()).isNotNull();
        assertThat(categoryRepository.findById(saved.getId()))
                .get()
                .extracting(Category::getName)
                .isEqualTo("Laptops");
    }

    @Test
    void existsByNameIgnoreCase_matchesAnyCasing() {
        categoryRepository.saveAndFlush(new Category("Laptops"));

        assertThat(categoryRepository.existsByNameIgnoreCase("Laptops")).isTrue();
        assertThat(categoryRepository.existsByNameIgnoreCase("LAPTOPS")).isTrue();
        assertThat(categoryRepository.existsByNameIgnoreCase("Phones")).isFalse();
    }

    @Test
    void existsByNameIgnoreCaseAndIdNot_excludesGivenCategory() {
        Category laptops = categoryRepository.saveAndFlush(new Category("Laptops"));

        assertThat(categoryRepository.existsByNameIgnoreCaseAndIdNot("laptops", laptops.getId())).isFalse();
        assertThat(categoryRepository.existsByNameIgnoreCaseAndIdNot("laptops", UUID.randomUUID())).isTrue();
    }

    @Test
    void save_nameDifferingOnlyByCase_violatesUniqueIndex() {
        categoryRepository.saveAndFlush(new Category("Laptops"));

        assertThatThrownBy(() -> categoryRepository.saveAndFlush(new Category("laptops")))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class, e ->
                        assertThat(ConstraintViolations.isViolationOf(e, ConstraintViolations.CATEGORY_NAME_UNIQUE)).isTrue());
    }
}
