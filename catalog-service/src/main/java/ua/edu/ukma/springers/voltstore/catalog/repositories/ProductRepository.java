package ua.edu.ukma.springers.voltstore.catalog.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;

import java.util.UUID;

// Batch lookup by IDs (EP-CAT-09) uses the inherited findAllById.
// Products are never hard-deleted: services must not call the inherited delete methods.
@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {
    boolean existsByCategoryId(UUID categoryId);
}
