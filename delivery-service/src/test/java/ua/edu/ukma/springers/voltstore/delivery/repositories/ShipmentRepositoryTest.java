package ua.edu.ukma.springers.voltstore.delivery.repositories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ua.edu.ukma.springers.voltstore.delivery.PostgresTestContainerConfig;
import ua.edu.ukma.springers.voltstore.delivery.carrier.Parcel;
import ua.edu.ukma.springers.voltstore.delivery.entities.Shipment;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus;
import ua.edu.ukma.springers.voltstore.delivery.support.Shipments;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Hibernate validate runs on context start, so a passing test also proves the entity matches the V1 schema
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
// Each repository call commits on its own, so a constraint violation surfaces at the call that caused it
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ShipmentRepositoryTest {
    private static final Parcel PARCEL = new Parcel(100, 10, 10, 10);

    @Autowired
    private ShipmentRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM shipment_items");
        jdbc.update("DELETE FROM shipments");
    }

    @Test
    void savedShipment_isStoredWithItemsAndTimestamps() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        repository.save(Shipments.pending(orderId, productId));

        Shipment found = repository.findByOrderId(orderId).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(ShipmentStatus.TTN_PENDING);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT quantity FROM shipment_items WHERE product_id = ?", Integer.class,
                productId)).isEqualTo(1);
    }

    @Test
    void orderId_isUnique() {
        UUID orderId = UUID.randomUUID();
        repository.save(Shipments.pending(orderId));

        assertThatThrownBy(() -> repository.save(Shipment.cancelledTombstone(orderId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tombstone_isStoredWithoutRecipient_butOtherStatusesNeedIt() {
        UUID orderId = UUID.randomUUID();
        repository.save(Shipment.cancelledTombstone(orderId));
        assertThat(repository.findByOrderId(orderId).orElseThrow().isTombstone()).isTrue();

        UUID other = UUID.randomUUID();
        repository.save(Shipments.pending(other));
        assertThatThrownBy(() -> jdbc.update("UPDATE shipments SET recipient_name = NULL WHERE order_id = ?", other))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void version_increasesOnEveryUpdate() {
        Shipment saved = repository.save(Shipments.pending(UUID.randomUUID()));
        long initial = saved.getVersion();

        saved.markGenerated("20450000000001", PARCEL);
        Shipment updated = repository.save(saved);

        assertThat(updated.getVersion()).isEqualTo(initial + 1);
    }

    @Test
    void ttn_isUnique() {
        Shipment first = Shipments.pending(UUID.randomUUID());
        first.markGenerated("20450000000001", PARCEL);
        repository.save(first);
        Shipment second = Shipments.pending(UUID.randomUUID());
        second.markGenerated("20450000000001", PARCEL);

        assertThatThrownBy(() -> repository.save(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void finders_byTtnOrderIdsAndStatus() {
        Shipment generated = Shipments.pending(UUID.randomUUID());
        generated.markGenerated("20450000000002", PARCEL);
        repository.save(generated);
        Shipment pending = repository.save(Shipments.pending(UUID.randomUUID()));

        assertThat(repository.findByTtn("20450000000002").orElseThrow().getOrderId())
                .isEqualTo(generated.getOrderId());
        assertThat(repository.findAllByOrderIdIn(List.of(generated.getOrderId(), pending.getOrderId(),
                UUID.randomUUID()))).hasSize(2);
        assertThat(repository.findAllByStatus(ShipmentStatus.TTN_GENERATED))
                .extracting(Shipment::getOrderId).containsExactly(generated.getOrderId());
    }

    @Test
    void quantityAndParcelValues_areCheckedByTheDatabaseToo() {
        Shipment saved = repository.save(Shipments.pending(UUID.randomUUID()));

        assertThatThrownBy(() -> jdbc.update("INSERT INTO shipment_items VALUES (?, ?, 0)", saved.getId(),
                UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE shipments SET height_cm = 0 WHERE id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE shipments SET status = 'SHIPPED' WHERE id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
