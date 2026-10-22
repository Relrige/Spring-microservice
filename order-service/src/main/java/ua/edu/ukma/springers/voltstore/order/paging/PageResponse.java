package ua.edu.ukma.springers.voltstore.order.paging;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The paging envelope shared by all list endpoints of VoltStore services (same as Inventory's). The Spring
 * {@code Page} type is deliberately not serialized directly: its JSON carries framework internals and is not a
 * stable contract. {@code page} is zero-based; {@code size} is the effective page size (after defaulting).
 */
public record PageResponse<T>(@Schema(requiredMode = REQUIRED) List<T> content,
                              @Schema(requiredMode = REQUIRED) int page,
                              @Schema(requiredMode = REQUIRED) int size,
                              @Schema(requiredMode = REQUIRED) long totalElements) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements());
    }
}
