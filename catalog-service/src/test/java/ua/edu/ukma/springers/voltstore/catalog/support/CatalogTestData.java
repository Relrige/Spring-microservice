package ua.edu.ukma.springers.voltstore.catalog.support;

import org.springframework.jdbc.core.JdbcTemplate;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;

import java.util.UUID;

/**
 * Inserts rows directly with SQL, so integration tests can set up states (e.g. an ACTIVE product)
 * that no endpoint can produce yet.
 */
public final class CatalogTestData {
    private CatalogTestData() {
    }

    public static void clean(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM categories");
    }

    // A valid EP-CAT-01 request body
    public static String productJson(UUID categoryId) {
        return "{\"title\":\"Laptop\",\"description\":\"A laptop\",\"categoryId\":\"" + categoryId + "\",\"basePrice\":999.99}";
    }

    public static UUID insertCategory(JdbcTemplate jdbc, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO categories (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    public static UUID insertProduct(JdbcTemplate jdbc, UUID categoryId, ProductStatus status, StockStatus stockStatus) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products (id, title, description, category_id, base_price, status, stock_status)
                VALUES (?, 'Laptop', 'A laptop', ?, 999.99, ?, ?)
                """, id, categoryId, status.name(), stockStatus.name());
        return id;
    }
}
