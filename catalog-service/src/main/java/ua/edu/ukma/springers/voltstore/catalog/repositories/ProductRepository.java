package ua.edu.ukma.springers.voltstore.catalog.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ua.edu.ukma.springers.voltstore.catalog.domain.entity.Product;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    boolean existsBySku(String sku);

    Optional<Product> findByIdAndDeletedFalse(UUID id);

    List<Product> findAllByDeletedFalse();
}