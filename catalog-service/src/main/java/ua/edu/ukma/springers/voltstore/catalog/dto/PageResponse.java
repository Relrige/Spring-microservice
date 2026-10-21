package ua.edu.ukma.springers.voltstore.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

// Paging envelope shared by the list endpoints: { content, page, size, totalElements }; page is zero-based, size is
// the effective page size (the requested one or the default), not the number of items in content
@Data
@AllArgsConstructor
public class PageResponse<T> {
    private List<T> content;
    private int page;
    private int size;
    private long totalElements;

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(),
                page.getSize(), page.getTotalElements());
    }
}
