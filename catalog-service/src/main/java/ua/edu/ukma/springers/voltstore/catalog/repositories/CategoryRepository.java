package ua.edu.ukma.springers.voltstore.catalog.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ua.edu.ukma.springers.voltstore.catalog.entities.Category;

import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {
    // lower() on both sides mirrors the categories_name_lower_key index, so this pre-check and the
    // database constraint always agree on what counts as a duplicate
    @Query("select count(c) > 0 from Category c where lower(c.name) = lower(:name)")
    boolean existsByNameIgnoreCase(String name);

    // Used by rename: the category being renamed must not conflict with itself
    @Query("select count(c) > 0 from Category c where lower(c.name) = lower(:name) and c.id <> :id")
    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);
}
