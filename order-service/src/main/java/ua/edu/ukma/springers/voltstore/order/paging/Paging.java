package ua.edu.ukma.springers.voltstore.order.paging;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** The {@code page} and {@code size} limits of Inventory's lists: zero-based page, size 1–100, default 20. */
public final class Paging {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private Paging() {
    }

    public static Pageable of(int page, int size, Sort sort) {
        if (page < 0) {
            throw new InvalidQueryParameterException("page", "page must not be negative");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidQueryParameterException("size", "size must be between 1 and " + MAX_SIZE);
        }
        return PageRequest.of(page, size, sort);
    }
}
