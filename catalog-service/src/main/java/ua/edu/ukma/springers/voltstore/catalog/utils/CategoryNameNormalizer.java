package ua.edu.ukma.springers.voltstore.catalog.utils;

/**
 * The single place where category names are normalized before they are stored or compared.
 * Case is kept as typed (uniqueness ignores it via lower() in queries and the unique index).
 */
public final class CategoryNameNormalizer {
    private CategoryNameNormalizer() {
    }

    public static String normalize(String name) {
        return name.trim();
    }
}
