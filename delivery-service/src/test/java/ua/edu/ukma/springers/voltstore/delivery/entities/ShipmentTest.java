package ua.edu.ukma.springers.voltstore.delivery.entities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ua.edu.ukma.springers.voltstore.delivery.carrier.Parcel;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.InvalidShipmentStateException;
import ua.edu.ukma.springers.voltstore.delivery.support.Shipments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus.CANCELLED;
import static ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus.DELIVERED;
import static ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus.IN_TRANSIT;
import static ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus.TTN_FAILED;
import static ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus.TTN_GENERATED;
import static ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentStatus.TTN_PENDING;

class ShipmentTest {
    private static final Parcel PARCEL = new Parcel(1500, 40, 30, 20);
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Test
    void pending_isDueImmediatelyWithoutAttempts_andMergesLinesOfTheSameProduct() {
        UUID productId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        Shipment shipment = Shipment.pending(orderId, Shipments.RECIPIENT, "NOVA_POSHTA",
                List.of(new ShipmentItem(productId, 2), new ShipmentItem(productId, 1)),
                new BigDecimal("1500.00"), "UAH", NOW);

        assertThat(shipment.getStatus()).isEqualTo(TTN_PENDING);
        assertThat(shipment.getOrderId()).isEqualTo(orderId);
        assertThat(shipment.getAttempts()).isZero();
        assertThat(shipment.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(shipment.getItems()).containsExactly(new ShipmentItem(productId, 3));
        assertThat(shipment.getRecipient()).isEqualTo(Shipments.RECIPIENT);
        assertThat(shipment.isTombstone()).isFalse();
    }

    @Test
    void pending_requiresItems() {
        assertThatThrownBy(() -> Shipment.pending(UUID.randomUUID(), Shipments.RECIPIENT, "NOVA_POSHTA", List.of(),
                BigDecimal.ONE, "UAH", NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tombstone_isCancelledWithoutRecipient() {
        Shipment tombstone = Shipment.cancelledTombstone(UUID.randomUUID());

        assertThat(tombstone.getStatus()).isEqualTo(CANCELLED);
        assertThat(tombstone.isTombstone()).isTrue();
        assertThat(tombstone.getRecipient()).isNull();
        assertThat(tombstone.getItems()).isEmpty();
    }

    @Test
    void markGenerated_fromPending_storesTtnAndParcelAndClearsReason() {
        Shipment shipment = inStatus(TTN_PENDING);
        shipment.recordFailedAttempt("carrier_error: timeout", NOW.plusSeconds(5));

        shipment.markGenerated("20450000000001", PARCEL);

        assertThat(shipment.getStatus()).isEqualTo(TTN_GENERATED);
        assertThat(shipment.getTtn()).isEqualTo("20450000000001");
        assertThat(shipment.getTotalWeightGrams()).isEqualTo(1500);
        assertThat(shipment.getLengthCm()).isEqualTo(40);
        assertThat(shipment.getWidthCm()).isEqualTo(30);
        assertThat(shipment.getHeightCm()).isEqualTo(20);
        assertThat(shipment.getFailureReason()).isNull();
        assertThat(shipment.getNextAttemptAt()).isNull();
    }

    @Test
    void markGenerated_fromFailed_isTheManualRetry() {
        Shipment shipment = inStatus(TTN_FAILED);

        shipment.markGenerated("20450000000001", PARCEL);

        assertThat(shipment.getStatus()).isEqualTo(TTN_GENERATED);
    }

    @Test
    void recordFailedAttempt_countsAndSchedulesTheNextAttempt() {
        Shipment shipment = inStatus(TTN_PENDING);

        shipment.recordFailedAttempt("inventory_unavailable", NOW.plusSeconds(30));

        assertThat(shipment.getStatus()).isEqualTo(TTN_PENDING);
        assertThat(shipment.getAttempts()).isEqualTo(1);
        assertThat(shipment.getFailureReason()).isEqualTo("inventory_unavailable");
        assertThat(shipment.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void markFailed_fromPending_andAgainFromFailedReplacesTheReason() {
        Shipment shipment = inStatus(TTN_PENDING);

        shipment.markFailed("packaging_data_missing:x");
        shipment.markFailed("carrier_error: down");

        assertThat(shipment.getStatus()).isEqualTo(TTN_FAILED);
        assertThat(shipment.getFailureReason()).isEqualTo("carrier_error: down");
        assertThat(shipment.getAttempts()).isEqualTo(2);
        assertThat(shipment.getNextAttemptAt()).isNull();
    }

    @Test
    void longReasons_areTruncatedToTheColumnLength() {
        Shipment shipment = inStatus(TTN_PENDING);

        shipment.markFailed("x".repeat(5000));

        assertThat(shipment.getFailureReason()).hasSize(Shipment.FAILURE_REASON_MAX_LENGTH);
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"TTN_PENDING", "TTN_FAILED", "TTN_GENERATED"})
    void cancel_isAllowedUntilHandover(ShipmentStatus status) {
        Shipment shipment = inStatus(status);

        shipment.cancel();

        assertThat(shipment.getStatus()).isEqualTo(CANCELLED);
    }

    @Test
    void cancel_keepsAGeneratedTtn_andRecordsACarrierFailure() {
        Shipment shipment = inStatus(TTN_GENERATED);

        shipment.cancel();
        shipment.recordCarrierCancellationFailure("carrier down");

        assertThat(shipment.getTtn()).isNotNull();
        assertThat(shipment.getFailureReason()).isEqualTo("carrier_cancellation_failed: carrier down");
    }

    @Test
    void handoverAndDelivery_followTheLifecycle() {
        Shipment shipment = inStatus(TTN_GENERATED);

        shipment.markInTransit(NOW);
        assertThat(shipment.getStatus()).isEqualTo(IN_TRANSIT);
        assertThat(shipment.getHandedOverAt()).isEqualTo(NOW);

        shipment.markDelivered();
        assertThat(shipment.getStatus()).isEqualTo(DELIVERED);
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"TTN_GENERATED", "IN_TRANSIT", "DELIVERED", "CANCELLED"})
    void markGenerated_isForbiddenOutsidePendingAndFailed(ShipmentStatus status) {
        assertForbidden(status, shipment -> shipment.markGenerated("1", PARCEL));
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = "TTN_PENDING", mode = EnumSource.Mode.EXCLUDE)
    void recordFailedAttempt_isOnlyForPending(ShipmentStatus status) {
        assertForbidden(status, shipment -> shipment.recordFailedAttempt("r", NOW));
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"TTN_GENERATED", "IN_TRANSIT", "DELIVERED", "CANCELLED"})
    void markFailed_isForbiddenOutsidePendingAndFailed(ShipmentStatus status) {
        assertForbidden(status, shipment -> shipment.markFailed("r"));
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"IN_TRANSIT", "DELIVERED", "CANCELLED"})
    void cancel_isForbiddenAfterHandoverOrWhenAlreadyCancelled(ShipmentStatus status) {
        assertForbidden(status, Shipment::cancel);
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = "TTN_GENERATED", mode = EnumSource.Mode.EXCLUDE)
    void markInTransit_isOnlyForGenerated(ShipmentStatus status) {
        assertForbidden(status, shipment -> shipment.markInTransit(NOW));
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = "IN_TRANSIT", mode = EnumSource.Mode.EXCLUDE)
    void markDelivered_isOnlyForInTransit(ShipmentStatus status) {
        assertForbidden(status, Shipment::markDelivered);
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = "CANCELLED", mode = EnumSource.Mode.EXCLUDE)
    void carrierCancellationFailure_isOnlyForCancelled(ShipmentStatus status) {
        assertForbidden(status, shipment -> shipment.recordCarrierCancellationFailure("e"));
    }

    private static void assertForbidden(ShipmentStatus status, Consumer<Shipment> transition) {
        Shipment shipment = inStatus(status);
        assertThatThrownBy(() -> transition.accept(shipment))
                .isInstanceOf(InvalidShipmentStateException.class)
                .hasMessageContaining(status.name());
        assertThat(shipment.getStatus()).isEqualTo(status);
    }

    // Drives a new shipment to the status through the allowed transitions
    private static Shipment inStatus(ShipmentStatus status) {
        Shipment shipment = Shipments.pending(UUID.randomUUID());
        switch (status) {
            case TTN_PENDING -> {
            }
            case TTN_FAILED -> shipment.markFailed("carrier_error: down");
            case TTN_GENERATED -> shipment.markGenerated("20450000000001", PARCEL);
            case IN_TRANSIT -> {
                shipment.markGenerated("20450000000001", PARCEL);
                shipment.markInTransit(NOW);
            }
            case DELIVERED -> {
                shipment.markGenerated("20450000000001", PARCEL);
                shipment.markInTransit(NOW);
                shipment.markDelivered();
            }
            case CANCELLED -> shipment.cancel();
        }
        return shipment;
    }
}
