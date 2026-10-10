package ua.edu.ukma.springers.voltstore.catalog.repositories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import ua.edu.ukma.springers.voltstore.catalog.PostgresContainerConfiguration;
import ua.edu.ukma.springers.voltstore.catalog.entities.Category;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;
import ua.edu.ukma.springers.voltstore.catalog.utils.ConstraintViolations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PostgresContainerConfiguration.class)
class ProductRepositoryTest {
    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID categoryId;

    @BeforeEach
    void createCategory() {
        categoryId = categoryRepository.saveAndFlush(new Category("Laptops")).getId();
    }

    @Test
    void save_persistsAllFieldsWithDefaults() {
        Product saved = productRepository.saveAndFlush(product(categoryId, "999.99"));

        assertThat(saved.getId()).isNotNull();
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM products WHERE id = ?", saved.getId());
        assertThat(row.get("title")).isEqualTo("Laptop");
        assertThat(row.get("description")).isEqualTo("A laptop");
        assertThat(row.get("category_id")).isEqualTo(categoryId);
        assertThat(row.get("base_price")).isEqualTo(new BigDecimal("999.99"));
        // Enums are stored by name, not by ordinal
        assertThat(row.get("status")).isEqualTo(ProductStatus.NOT_ACTIVE.name());
        assertThat(row.get("stock_status")).isEqualTo(StockStatus.OUT_OF_STOCK.name());
    }

    @Test
    void save_longDescription_isNotTruncated() {
        String description = "d".repeat(Product.DESCRIPTION_MAX_LENGTH);
        Product saved = productRepository.saveAndFlush(
                Product.createNew("Laptop", description, categoryId, new BigDecimal("10")));

        String stored = jdbcTemplate.queryForObject("SELECT description FROM products WHERE id = ?", String.class, saved.getId());
        assertThat(stored).hasSize(Product.DESCRIPTION_MAX_LENGTH);
    }

    @Test
    void save_unknownCategory_violatesForeignKey() {
        assertThatThrownBy(() -> productRepository.saveAndFlush(product(UUID.randomUUID(), "10")))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class, e ->
                        assertThat(ConstraintViolations.isViolationOf(e, ConstraintViolations.PRODUCT_CATEGORY_FK)).isTrue());
    }

    @Test
    void save_zeroPrice_violatesCheckConstraint() {
        assertThatThrownBy(() -> productRepository.saveAndFlush(product(categoryId, "0")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_negativePrice_violatesCheckConstraint() {
        assertThatThrownBy(() -> productRepository.saveAndFlush(product(categoryId, "-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deleteCategory_withProducts_isRestrictedByForeignKey() {
        productRepository.saveAndFlush(product(categoryId, "10"));

        assertThatThrownBy(() -> {
            categoryRepository.deleteById(categoryId);
            categoryRepository.flush();
        }).isInstanceOfSatisfying(DataIntegrityViolationException.class, e ->
                assertThat(ConstraintViolations.isViolationOf(e, ConstraintViolations.PRODUCT_CATEGORY_FK)).isTrue());
    }

    @Test
    void existsByCategoryId_reflectsAssignedProducts() {
        UUID emptyCategoryId = categoryRepository.saveAndFlush(new Category("Phones")).getId();
        productRepository.saveAndFlush(product(categoryId, "10"));

        assertThat(productRepository.existsByCategoryId(categoryId)).isTrue();
        assertThat(productRepository.existsByCategoryId(emptyCategoryId)).isFalse();
    }

    @Test
    void findAllById_returnsOnlyExistingProducts() {
        Product first = productRepository.saveAndFlush(product(categoryId, "10"));
        Product second = productRepository.saveAndFlush(product(categoryId, "20"));

        List<Product> found = productRepository.findAllById(List.of(first.getId(), second.getId(), UUID.randomUUID()));

        assertThat(found).extracting(Product::getId).containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    private static Product product(UUID categoryId, String price) {
        return Product.createNew("Laptop", "A laptop", categoryId, new BigDecimal(price));
    }
}
