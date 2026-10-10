package ua.edu.ukma.springers.voltstore.catalog.utils;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class ConstraintViolationsTest {

    @Test
    void matchesConstraintNameExtractedByHibernate() {
        DataIntegrityViolationException e = violation("violation", "fk_products_category");

        assertThat(ConstraintViolations.isViolationOf(e, "fk_products_category")).isTrue();
        assertThat(ConstraintViolations.isViolationOf(e, "categories_name_lower_key")).isFalse();
    }

    @Test
    void fallsBackToSqlMessageWhenHibernateCannotExtractName() {
        // PostgreSQL 18 wording for a delete blocked by ON DELETE RESTRICT
        String message = "ERROR: update or delete on table \"categories\" violates RESTRICT setting of "
                + "foreign key constraint \"fk_products_category\" on table \"products\"";
        DataIntegrityViolationException e = violation(message, null);

        assertThat(ConstraintViolations.isViolationOf(e, "fk_products_category")).isTrue();
        assertThat(ConstraintViolations.isViolationOf(e, "fk_products")).isFalse();
    }

    private static DataIntegrityViolationException violation(String sqlMessage, String constraintName) {
        return new DataIntegrityViolationException("violation",
                new ConstraintViolationException("violation", new SQLException(sqlMessage), constraintName));
    }
}
