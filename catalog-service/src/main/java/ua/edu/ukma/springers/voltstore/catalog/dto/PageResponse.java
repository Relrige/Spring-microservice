package ua.edu.ukma.springers.voltstore.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

// Paging envelope shared by the list endpoints: { content, page, size, totalElements }; page is zero-based, size is
// the effective page size (the requested one or the default), not the number of items in content
@Data
@AllArgsConstructor
// @Schema(requiredMode = REQUIRED) marks a field that is never null (OpenAPI conventions D5); unmarked fields may be
// null or missing
public class PageResponse<T> {
    @Schema(requiredMode = REQUIRED)
    private List<T> content;
    @Schema(requiredMode = REQUIRED)
    private int page;
    @Schema(requiredMode = REQUIRED)
    private int size;
    @Schema(requiredMode = REQUIRED)
    private long totalElements;

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(),
                page.getSize(), page.getTotalElements());
    }
}
