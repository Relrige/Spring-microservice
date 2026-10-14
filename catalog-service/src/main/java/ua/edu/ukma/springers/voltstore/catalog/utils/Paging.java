package ua.edu.ukma.springers.voltstore.catalog.utils;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Paging rules shared by the list endpoints (categories and products). {@code page} is zero-based.
 * The limits are enforced on the query DTOs with Bean Validation; this class only applies the defaults.
 */
public final class Paging {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private Paging() {
    }

    public static PageRequest pageRequest(@Nullable Integer page, @Nullable Integer size, Sort sort) {
        return PageRequest.of(page != null ? page : 0, size != null ? size : DEFAULT_SIZE, sort);
    }
}
