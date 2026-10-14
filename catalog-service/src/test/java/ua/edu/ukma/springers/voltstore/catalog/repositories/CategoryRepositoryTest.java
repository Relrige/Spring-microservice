package ua.edu.ukma.springers.voltstore.catalog.repositories;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    // --- EP-CAT-10: search and paging ---

    @Test
    void findByNameContainingIgnoreCase_matchesSubstringInAnyCasing() {
        save("Gaming Laptops", "Phones", "laptop bags");

        Page<Category> page = categoryRepository.findByNameContainingIgnoreCase("LAPTOP", byName(0, 10));

        assertThat(page.getContent()).extracting(Category::getName).containsExactly("Gaming Laptops", "laptop bags");
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void findByNameContainingIgnoreCase_treatsLikeWildcardsLiterally() {
        save("50% off", "500 GB drives", "USB_C cables", "USB-C hubs");

        assertThat(categoryRepository.findByNameContainingIgnoreCase("50%", byName(0, 10)).getContent())
                .extracting(Category::getName).containsExactly("50% off");
        assertThat(categoryRepository.findByNameContainingIgnoreCase("USB_C", byName(0, 10)).getContent())
                .extracting(Category::getName).containsExactly("USB_C cables");
    }

    @Test
    void findAll_pagesInStableNameOrder() {
        save("Cameras", "Audio", "Laptops", "Phones", "Drones");

        assertThat(categoryRepository.findAll(byName(0, 2)).getContent())
                .extracting(Category::getName).containsExactly("Audio", "Cameras");
        Page<Category> last = categoryRepository.findAll(byName(2, 2));
        assertThat(last.getContent()).extracting(Category::getName).containsExactly("Phones");
        assertThat(last.getTotalElements()).isEqualTo(5);
    }

    private void save(String... names) {
        for (String name : names) {
            categoryRepository.saveAndFlush(new Category(name));
        }
    }

    private static PageRequest byName(int page, int size) {
        return PageRequest.of(page, size, Sort.by("name", "id"));
    }
}
