package ua.edu.ukma.springers.voltstore.inventory.repositories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import ua.edu.ukma.springers.voltstore.inventory.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.inventory.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.inventory.entities.StockItemEntity;
import ua.edu.ukma.springers.voltstore.inventory.specifications.StockItemSpecifications;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PostgresTestContainerConfig.class)
class StockItemRepositoryTest {
    @Autowired
    private StockItemRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTable() {
        jdbcTemplate.update("DELETE FROM stock_item_reservations");
        jdbcTemplate.update("DELETE FROM stock_items");
    }

    @Test
    void save_appliesDefaultsAndKeepsAssignedId() {
        UUID productId = UUID.randomUUID();

        repository.saveAndFlush(StockItemEntity.builder().productId(productId).build());

        StockItemEntity found = repository.findById(productId).orElseThrow();
        assertThat(found.getProductId()).isEqualTo(productId);
        assertThat(found.getAvailableQuantity()).isZero();
        assertThat(found.getReservedQuantity()).isZero();
        assertThat(found.getGrossWeightGrams()).isNull();
        assertThat(found.getLengthCm()).isNull();
        assertThat(found.getWidthCm()).isNull();
        assertThat(found.getHeightCm()).isNull();
    }

    @Test
    void save_negativeAvailableQuantity_violatesCheck() {
        StockItemEntity item = StockItemEntity.builder().productId(UUID.randomUUID()).availableQuantity(-1).build();

        assertThatThrownBy(() -> repository.saveAndFlush(item))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_negativeReservedQuantity_violatesCheck() {
        StockItemEntity item = StockItemEntity.builder().productId(UUID.randomUUID()).reservedQuantity(-1).build();

        assertThatThrownBy(() -> repository.saveAndFlush(item))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void save_nonPositiveGrossWeight_violatesCheck(int value) {
        assertPackagingRejected(StockItemEntity.builder().grossWeightGrams(value));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void save_nonPositiveLength_violatesCheck(int value) {
        assertPackagingRejected(StockItemEntity.builder().lengthCm(value));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void save_nonPositiveWidth_violatesCheck(int value) {
        assertPackagingRejected(StockItemEntity.builder().widthCm(value));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void save_nonPositiveHeight_violatesCheck(int value) {
        assertPackagingRejected(StockItemEntity.builder().heightCm(value));
    }

    @Test
    void isPackagingConfigured_noDimensions_false() {
        assertThat(StockItemEntity.builder().build().isPackagingConfigured()).isFalse();
    }

    @Test
    void isPackagingConfigured_partialDimensions_false() {
        StockItemEntity item = StockItemEntity.builder().grossWeightGrams(500).lengthCm(10).widthCm(5).build();

        assertThat(item.isPackagingConfigured()).isFalse();
    }

    @Test
    void isPackagingConfigured_allDimensions_true() {
        StockItemEntity item = StockItemEntity.builder()
                .grossWeightGrams(500).lengthCm(10).widthCm(5).heightCm(2).build();

        assertThat(item.isPackagingConfigured()).isTrue();
    }

    @Test
    void getTotalQuantity_sumsAvailableAndReserved() {
        StockItemEntity item = StockItemEntity.builder().availableQuantity(7).reservedQuantity(3).build();

        assertThat(item.getTotalQuantity()).isEqualTo(10);
    }

    @Test
    void findByIdForUpdate_returnsOnlyRequestedItem() {
        StockItemEntity first = repository.saveAndFlush(item(UUID.randomUUID()));
        repository.saveAndFlush(item(UUID.randomUUID()));

        Optional<StockItemEntity> found = repository.findByIdForUpdate(first.getProductId());

        assertThat(found).isNotEmpty().contains(first);
    }

    @Test
    void findAllByIdsForUpdate_returnsRequestedItemsOrderedByProductId() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        repository.saveAndFlush(item(c));
        repository.saveAndFlush(item(a));
        repository.saveAndFlush(item(b));
        repository.saveAndFlush(item(UUID.randomUUID()));

        List<StockItemEntity> found = repository.findAllByIdsForUpdate(List.of(c, a, b));

        assertThat(found).extracting(StockItemEntity::getProductId).containsExactly(a, b, c);
    }

    @Test
    void packagingConfiguredSpecification_matchesJavaDerivationForEveryNullCombination() {
        // The CHECK constraints forbid zero/negative values, so a "missing" dimension is NULL in the database.
        // All 16 combinations of present/missing are stored and each side of the filter must agree with the entity.
        Integer[] values = {null, 1};
        for (Integer weight : values) {
            for (Integer length : values) {
                for (Integer width : values) {
                    for (Integer height : values) {
                        repository.saveAndFlush(StockItemEntity.builder().productId(UUID.randomUUID())
                                .grossWeightGrams(weight).lengthCm(length).widthCm(width).heightCm(height).build());
                    }
                }
            }
        }
        List<StockItemEntity> all = repository.findAll();
        assertThat(all).hasSize(16);

        List<StockItemEntity> configured = repository.findAll(StockItemSpecifications.packagingConfigured(true));
        List<StockItemEntity> notConfigured = repository.findAll(StockItemSpecifications.packagingConfigured(false));

        assertThat(configured).hasSize(1).allMatch(StockItemEntity::isPackagingConfigured);
        assertThat(notConfigured).hasSize(15).noneMatch(StockItemEntity::isPackagingConfigured);
    }

    @Test
    void findAll_withSpecificationAndPageable_pagesFilteredItemsInStableOrder() {
        UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        UUID d = UUID.fromString("00000000-0000-0000-0000-00000000000d");
        for (UUID id : List.of(d, b, c, a)) {
            // a and b carry the same quantity: the productId tiebreaker must order them
            repository.saveAndFlush(StockItemEntity.builder().productId(id)
                    .availableQuantity(id.equals(d) ? 9 : 4).build());
        }
        Sort sort = Sort.by(Sort.Order.desc("availableQuantity"), Sort.Order.asc("productId"));

        var firstPage = repository.findAll(StockItemSpecifications.packagingConfigured(false), PageRequest.of(0, 3, sort));
        var secondPage = repository.findAll(StockItemSpecifications.packagingConfigured(false), PageRequest.of(1, 3, sort));
        var configuredOnly = repository.findAll(StockItemSpecifications.packagingConfigured(true), PageRequest.of(0, 3, sort));

        assertThat(firstPage.getContent()).extracting(StockItemEntity::getProductId).containsExactly(d, a, b);
        assertThat(secondPage.getContent()).extracting(StockItemEntity::getProductId).containsExactly(c);
        assertThat(firstPage.getTotalElements()).isEqualTo(4);
        assertThat(configuredOnly.getContent()).isEmpty();
        assertThat(configuredOnly.getTotalElements()).isZero();
    }

    // --- copy of product data (task 22) ---

    @Test
    void upsertProductData_missingRow_createsItWithZeroQuantitiesAndTheCopy() {
        UUID productId = UUID.randomUUID();

        assertThat(repository.upsertProductData(productId, "Laptop", "NOT_ACTIVE", 0)).isEqualTo(1);

        StockItemEntity found = repository.findById(productId).orElseThrow();
        assertThat(found.getTitle()).isEqualTo("Laptop");
        assertThat(found.getProductStatus()).isEqualTo(ProductStatus.NOT_ACTIVE);
        assertThat(found.getProductVersion()).isZero();
        assertThat(found.getAvailableQuantity()).isZero();
        assertThat(found.getReservedQuantity()).isZero();
        assertThat(found.isPackagingConfigured()).isFalse();
    }

    @Test
    void upsertProductData_newerVersionWins_olderAndEqualChangeNothing() {
        UUID productId = UUID.randomUUID();
        repository.upsertProductData(productId, "Version two", "ACTIVE", 2);

        assertThat(repository.upsertProductData(productId, "Version one", "NOT_ACTIVE", 1)).isZero();
        assertThat(repository.upsertProductData(productId, "Version two again", "NOT_ACTIVE", 2)).isZero();
        assertThat(copyOf(productId)).containsExactly("Version two", "ACTIVE", 2L);

        assertThat(repository.upsertProductData(productId, "Version three", "NOT_ACTIVE", 3)).isEqualTo(1);
        assertThat(copyOf(productId)).containsExactly("Version three", "NOT_ACTIVE", 3L);
    }

    @Test
    void upsertProductData_existingRowWithoutCopy_getsTheCopy_andKeepsQuantitiesAndPackaging() {
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO stock_items (product_id, available_quantity, reserved_quantity, "
                + "gross_weight_grams, length_cm, width_cm, height_cm) VALUES (?, 7, 2, 500, 10, 5, 2)", productId);

        assertThat(repository.upsertProductData(productId, "Laptop", "ACTIVE", 0)).isEqualTo(1);

        assertThat(copyOf(productId)).containsExactly("Laptop", "ACTIVE", 0L);
        assertThat(jdbcTemplate.queryForList("SELECT available_quantity, reserved_quantity, gross_weight_grams FROM "
                + "stock_items WHERE product_id = ?", productId).getFirst().values()).containsExactly(7, 2, 500);
    }

    @Test
    void productStatusColumn_rejectsUnknownValues() {
        assertThatThrownBy(() -> repository.upsertProductData(UUID.randomUUID(), "Laptop", "ARCHIVED", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void savingAStockChange_neverWritesTheCopy() {
        UUID productId = UUID.randomUUID();
        repository.upsertProductData(productId, "Laptop", "ACTIVE", 1);
        StockItemEntity loaded = repository.findById(productId).orElseThrow();
        // A newer copy arrives while the entity is loaded (read-only mapping: the save must not restore the old one)
        jdbcTemplate.update("UPDATE stock_items SET title = 'Renamed', product_version = 2 WHERE product_id = ?",
                productId);

        loaded.setAvailableQuantity(9);
        repository.saveAndFlush(loaded);

        assertThat(copyOf(productId)).containsExactly("Renamed", "ACTIVE", 2L);
        assertThat(jdbcTemplate.queryForObject("SELECT available_quantity FROM stock_items WHERE product_id = ?",
                Integer.class, productId)).isEqualTo(9);
    }

    // --- stock list filters on the copy (task 24) ---

    @Test
    void titleContains_isCaseInsensitive_andMatchesPercentAndUnderscoreLiterally() {
        UUID laptop = copied("Gaming LAPTOP 15", "ACTIVE");
        UUID percent = copied("Charger 50% off", "ACTIVE");
        UUID underscore = copied("Cable_USB", "ACTIVE");
        copied("Charger 50 off", "ACTIVE");
        UUID cableDash = copied("Cable-USB", "ACTIVE");
        repository.insertIfAbsent(UUID.randomUUID());

        assertThat(idsMatching(StockItemSpecifications.titleContains("laptop"))).containsExactly(laptop);
        assertThat(idsMatching(StockItemSpecifications.titleContains("50%"))).containsExactly(percent);
        assertThat(idsMatching(StockItemSpecifications.titleContains("e_u"))).containsExactly(underscore);
        assertThat(idsMatching(StockItemSpecifications.titleContains("%"))).containsExactly(percent);
        assertThat(idsMatching(StockItemSpecifications.titleContains("cable"))).containsExactlyInAnyOrder(underscore,
                cableDash);
    }

    @Test
    void hasProductStatus_matchesTheCopiedStatus_rowsWithoutCopyMatchNeither() {
        UUID active = copied("A", "ACTIVE");
        UUID inactive = copied("B", "NOT_ACTIVE");
        repository.insertIfAbsent(UUID.randomUUID());

        assertThat(idsMatching(StockItemSpecifications.hasProductStatus(ProductStatus.ACTIVE)))
                .containsExactly(active);
        assertThat(idsMatching(StockItemSpecifications.hasProductStatus(ProductStatus.NOT_ACTIVE)))
                .containsExactly(inactive);
    }

    private UUID copied(String title, String status) {
        UUID productId = UUID.randomUUID();
        repository.upsertProductData(productId, title, status, 0);
        return productId;
    }

    private List<UUID> idsMatching(org.springframework.data.jpa.domain.Specification<StockItemEntity> spec) {
        return repository.findAll(spec).stream().map(StockItemEntity::getProductId).toList();
    }

    private List<Object> copyOf(UUID productId) {
        return List.copyOf(jdbcTemplate.queryForMap("SELECT title, product_status, product_version FROM stock_items "
                + "WHERE product_id = ?", productId).values());
    }

    private void assertPackagingRejected(StockItemEntity.StockItemEntityBuilder builder) {
        StockItemEntity item = builder.productId(UUID.randomUUID()).build();

        assertThatThrownBy(() -> repository.saveAndFlush(item))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private StockItemEntity item(UUID productId) {
        return StockItemEntity.builder().productId(productId).availableQuantity(5).build();
    }
}
