package ua.edu.ukma.springers.voltstore.inventory.paging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageableParserTest {
    private final PageableParser parser = new PageableParser(Map.of(
            "productId", "productId",
            "available", "availableQuantity"), "productId");

    @Test
    void noSortBy_ordersByTiebreakerAscending() {
        Pageable pageable = parser.parse(2, 20, null);

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "productId"));
    }

    @Test
    void fieldOnly_defaultsToAscendingAndAppendsTiebreaker() {
        Pageable pageable = parser.parse(0, 20, "available");

        assertThat(pageable.getSort()).isEqualTo(
                Sort.by(Sort.Order.asc("availableQuantity"), Sort.Order.asc("productId")));
    }

    @Test
    void fieldWithDirection_isCaseInsensitiveAndMapsPublicNameToProperty() {
        Pageable pageable = parser.parse(0, 20, "available,DESC");

        assertThat(pageable.getSort()).isEqualTo(
                Sort.by(Sort.Order.desc("availableQuantity"), Sort.Order.asc("productId")));
    }

    @Test
    void sortingByTiebreakerItself_keepsRequestedDirectionWithoutDuplicate() {
        Pageable pageable = parser.parse(0, 20, "productId,desc");

        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "productId"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "availableQuantity", "available,up", "available,", "available,asc,desc",
            ",asc", "available;drop", "AVAILABLE"})
    void invalidSortBy_isRejected(String sortBy) {
        assertThatThrownBy(() -> parser.parse(0, 20, sortBy))
                .isInstanceOfSatisfying(InvalidQueryParameterException.class,
                        ex -> assertThat(ex.getParameter()).isEqualTo("sortBy"));
    }

    @Test
    void negativePage_isRejected() {
        assertThatThrownBy(() -> parser.parse(-1, 20, null))
                .isInstanceOfSatisfying(InvalidQueryParameterException.class,
                        ex -> assertThat(ex.getParameter()).isEqualTo("page"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5, 101})
    void sizeOutsideRange_isRejected(int size) {
        assertThatThrownBy(() -> parser.parse(0, size, null))
                .isInstanceOfSatisfying(InvalidQueryParameterException.class,
                        ex -> assertThat(ex.getParameter()).isEqualTo("size"));
    }

    @Test
    void sizeAtBounds_isAccepted() {
        assertThat(parser.parse(0, 1, null).getPageSize()).isEqualTo(1);
        assertThat(parser.parse(0, 100, null).getPageSize()).isEqualTo(100);
    }
}
