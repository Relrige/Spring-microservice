package ua.edu.ukma.springers.voltstore.catalog.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.StockStatus;

import java.util.UUID;

// Batch lookup by IDs (EP-CAT-09) uses the inherited findAllById (a single IN query).
// Browsing (EP-CAT-04) composes optional filters with ProductSpecifications.
// Products are never hard-deleted: services must not call the inherited delete methods.
@Repository
public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {
    boolean existsByCategoryId(UUID categoryId);

    // A single UPDATE of one column: no read-modify-write, so it can neither overwrite a concurrent manager
    // edit nor be overwritten by one (manager updates write only their changed columns, see @DynamicUpdate)
    @Modifying
    @Query("update Product p set p.stockStatus = :stockStatus where p.id = :id")
    int updateStockStatus(UUID id, StockStatus stockStatus);
}
