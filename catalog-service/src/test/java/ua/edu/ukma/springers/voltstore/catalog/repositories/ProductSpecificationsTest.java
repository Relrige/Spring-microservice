package ua.edu.ukma.springers.voltstore.catalog.repositories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.testcontainers.junit.jupiter.Testcontainers;
import ua.edu.ukma.springers.voltstore.catalog.PostgresContainerConfiguration;
import ua.edu.ukma.springers.voltstore.catalog.entities.Category;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.edu.ukma.springers.voltstore.catalog.repositories.ProductSpecifications.hasStatus;
import static ua.edu.ukma.springers.voltstore.catalog.repositories.ProductSpecifications.idIn;
import static ua.edu.ukma.springers.voltstore.catalog.repositories.ProductSpecifications.inCategory;
import static ua.edu.ukma.springers.voltstore.catalog.repositories.ProductSpecifications.priceAtLeast;
import static ua.edu.ukma.springers.voltstore.catalog.repositories.ProductSpecifications.priceAtMost;
import static ua.edu.ukma.springers.voltstore.catalog.repositories.ProductSpecifications.titleOrDescriptionContains;

// EP-CAT-04 filters on a real PostgreSQL: each filter alone, combined, and with paging and sorting
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PostgresContainerConfiguration.class)
class ProductSpecificationsTest {
    private static final Sort BY_TITLE = Sort.by("title", "id");

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private UUID laptops;
    private UUID phones;
    private UUID gamingLaptop;
    private UUID draftLaptop;

    @BeforeEach
    void setUp() {
        laptops = categoryRepository.saveAndFlush(new Category("Laptops")).getId();
        phones = categoryRepository.saveAndFlush(new Category("Phones")).getId();
        gamingLaptop = save("Gaming Laptop", "Fast GPU", laptops, "2000", true);
        save("Office Laptop", "Quiet and light", laptops, "800", true);
        draftLaptop = save("Draft Laptop", "Not published yet", laptops, "500", false);
        save("Budget Phone", "Great value, 50% off", phones, "150", true);
        save("Flagship Phone", "Best CAMERA", phones, "1200", false);
    }

    @Test
    void noFilters_returnsEverything() {
        assertThat(titles(Specification.allOf(List.of()))).hasSize(5);
    }

    @Test
    void hasStatus_filtersByStatus() {
        assertThat(titles(hasStatus(ProductStatus.ACTIVE)))
                .containsExactly("Budget Phone", "Gaming Laptop", "Office Laptop");
        assertThat(titles(hasStatus(ProductStatus.NOT_ACTIVE))).containsExactly("Draft Laptop", "Flagship Phone");
    }

    @Test
    void inCategory_filtersByCategoryAndUnknownCategoryMatchesNothing() {
        assertThat(titles(inCategory(phones))).containsExactly("Budget Phone", "Flagship Phone");
        assertThat(titles(inCategory(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void idIn_returnsOnlyTheListedProducts_andUnknownIdsMatchNothing() {
        assertThat(titles(idIn(List.of(gamingLaptop, draftLaptop, UUID.randomUUID()))))
                .containsExactly("Draft Laptop", "Gaming Laptop");
        assertThat(titles(idIn(List.of(UUID.randomUUID())))).isEmpty();
        assertThat(titles(Specification.allOf(idIn(List.of(gamingLaptop, draftLaptop)), hasStatus(ProductStatus.ACTIVE))))
                .containsExactly("Gaming Laptop");
    }

    @Test
    void priceBounds_areInclusive() {
        assertThat(titles(priceAtLeast(new BigDecimal("1200"))))
                .containsExactly("Flagship Phone", "Gaming Laptop");
        assertThat(titles(priceAtMost(new BigDecimal("500")))).containsExactly("Budget Phone", "Draft Laptop");
        assertThat(titles(Specification.allOf(priceAtLeast(new BigDecimal("500")), priceAtMost(new BigDecimal("800")))))
                .containsExactly("Draft Laptop", "Office Laptop");
    }

    @Test
    void search_matchesTitleOrDescriptionIgnoringCase() {
        assertThat(titles(titleOrDescriptionContains("laptop")))
                .containsExactly("Draft Laptop", "Gaming Laptop", "Office Laptop");
        assertThat(titles(titleOrDescriptionContains("camera"))).containsExactly("Flagship Phone");
        assertThat(titles(titleOrDescriptionContains("nothing like this"))).isEmpty();
    }

    @Test
    void search_treatsLikeWildcardsLiterally() {
        assertThat(titles(titleOrDescriptionContains("50%"))).containsExactly("Budget Phone");
        assertThat(titles(titleOrDescriptionContains("%"))).containsExactly("Budget Phone");
        assertThat(titles(titleOrDescriptionContains("_"))).isEmpty();
    }

    @Test
    void combinedFilters_allMustMatch() {
        Specification<Product> spec = Specification.allOf(hasStatus(ProductStatus.ACTIVE), inCategory(laptops),
                priceAtMost(new BigDecimal("1000")), titleOrDescriptionContains("laptop"));

        assertThat(titles(spec)).containsExactly("Office Laptop");
    }

    @Test
    void paging_andSortingByPriceDescending() {
        Sort byPriceDesc = Sort.by(Sort.Direction.DESC, "basePrice").and(Sort.by("id"));

        Page<Product> first = productRepository.findAll(Specification.allOf(List.of()), PageRequest.of(0, 2, byPriceDesc));
        Page<Product> second = productRepository.findAll(Specification.allOf(List.of()), PageRequest.of(1, 2, byPriceDesc));

        assertThat(first.getContent()).extracting(Product::getTitle).containsExactly("Gaming Laptop", "Flagship Phone");
        assertThat(second.getContent()).extracting(Product::getTitle).containsExactly("Office Laptop", "Draft Laptop");
        assertThat(first.getTotalElements()).isEqualTo(5);
    }

    private List<String> titles(Specification<Product> spec) {
        return productRepository.findAll(spec, PageRequest.of(0, 50, BY_TITLE)).map(Product::getTitle).getContent();
    }

    private UUID save(String title, String description, UUID categoryId, String price, boolean active) {
        Product product = Product.createNew(title, description, categoryId, new BigDecimal(price));
        if (active) {
            product.activate();
        }
        return productRepository.saveAndFlush(product).getId();
    }
}
