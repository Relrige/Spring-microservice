package ua.edu.ukma.springers.voltstore.customer.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.customer.entities.CustomerProfile;

import java.util.UUID;

public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, UUID> {

    /**
     * Inserts the profile, or updates it if the customer already has one, in one atomic statement: two parallel
     * first saves end as one row (the later one wins) instead of a duplicate-key error. {@code created_at} is kept
     * on update. Joins the caller's transaction, or runs in its own.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO customer_profiles (customer_id, first_name, last_name, phone, default_address,
                                           created_at, updated_at)
            VALUES (:customerId, :firstName, :lastName, :phone, :defaultAddress, now(), now())
            ON CONFLICT (customer_id) DO UPDATE
                SET first_name      = EXCLUDED.first_name,
                    last_name       = EXCLUDED.last_name,
                    phone           = EXCLUDED.phone,
                    default_address = EXCLUDED.default_address,
                    updated_at      = EXCLUDED.updated_at
            """, nativeQuery = true)
    void upsert(@Param("customerId") UUID customerId,
                @Param("firstName") String firstName,
                @Param("lastName") String lastName,
                @Param("phone") String phone,
                @Param("defaultAddress") String defaultAddress);
}
