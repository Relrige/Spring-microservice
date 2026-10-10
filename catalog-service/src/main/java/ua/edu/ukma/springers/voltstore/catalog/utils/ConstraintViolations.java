package ua.edu.ukma.springers.voltstore.catalog.utils;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

/**
 * Tells which database constraint caused a {@link DataIntegrityViolationException}, so that a lost race
 * against a constraint can be mapped to the same friendly error as the service-level pre-check.
 */
public final class ConstraintViolations {
    // Names are defined in the Flyway migrations
    public static final String CATEGORY_NAME_UNIQUE = "categories_name_lower_key";
    public static final String PRODUCT_CATEGORY_FK = "fk_products_category";

    private ConstraintViolations() {
    }

    public static boolean isViolationOf(DataIntegrityViolationException e, String constraintName) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException cve
                    && constraintName.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
            // Fallback when Hibernate cannot extract the name: PostgreSQL 18 reports a blocked delete as
            // 'violates RESTRICT setting of foreign key constraint "..."', a wording Hibernate does not parse
            if (cause instanceof SQLException sqlException && sqlException.getMessage() != null
                    && sqlException.getMessage().contains("\"" + constraintName + "\"")) {
                return true;
            }
        }
        return false;
    }
}
