package ua.edu.ukma.springers.voltstore.inventory.paging;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns the {@code page}, {@code size} and {@code sortBy} query parameters into a {@link Pageable}.
 * <p>
 * {@code sortBy} has the format {@code field} or {@code field,direction} (direction {@code asc} by default,
 * case-insensitive). Only fields from the whitelist are accepted, and the whitelist maps the public name to the
 * entity property, so the API does not expose (or depend on) entity internals. The tiebreaker property is always
 * appended as the last sort key, which keeps page boundaries deterministic when the sorted values are equal.
 * Without {@code sortBy} the result is ordered by the tiebreaker ascending. Properties listed as "nulls last" put
 * rows without a value at the end in both directions (PostgreSQL's default puts them first for {@code desc}).
 */
public class PageableParser {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private final Map<String, String> sortableFields;
    private final String tiebreakerProperty;
    private final Set<String> nullsLastProperties;

    public PageableParser(Map<String, String> sortableFields, String tiebreakerProperty) {
        this(sortableFields, tiebreakerProperty, Set.of());
    }

    public PageableParser(Map<String, String> sortableFields, String tiebreakerProperty,
                          Set<String> nullsLastProperties) {
        this.sortableFields = Map.copyOf(sortableFields);
        this.tiebreakerProperty = tiebreakerProperty;
        this.nullsLastProperties = Set.copyOf(nullsLastProperties);
    }

    public Pageable parse(int page, int size, String sortBy) {
        if (page < 0) {
            throw new InvalidQueryParameterException("page", "page must not be negative");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidQueryParameterException("size", "size must be between 1 and " + MAX_SIZE);
        }
        return PageRequest.of(page, size, parseSort(sortBy));
    }

    private Sort parseSort(String sortBy) {
        Sort tiebreaker = Sort.by(Sort.Direction.ASC, tiebreakerProperty);
        if (sortBy == null) {
            return tiebreaker;
        }
        String[] parts = sortBy.split(",", -1);
        if (parts.length > 2) {
            throw invalidSort();
        }
        String property = sortableFields.get(parts[0].trim());
        if (property == null) {
            throw invalidSort();
        }
        Sort.Direction direction = parts.length == 2 ? parseDirection(parts[1]) : Sort.Direction.ASC;
        Sort.Order order = new Sort.Order(direction, property);
        Sort requested = Sort.by(nullsLastProperties.contains(property) ? order.nullsLast() : order);
        return property.equals(tiebreakerProperty) ? requested : requested.and(tiebreaker);
    }

    private Sort.Direction parseDirection(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw invalidSort();
        };
    }

    private InvalidQueryParameterException invalidSort() {
        return new InvalidQueryParameterException("sortBy",
                "sortBy must be 'field' or 'field,asc|desc' where field is one of "
                        + new TreeSet<>(sortableFields.keySet()));
    }
}
