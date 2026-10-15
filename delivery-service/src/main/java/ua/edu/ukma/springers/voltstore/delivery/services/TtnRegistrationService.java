package ua.edu.ukma.springers.voltstore.delivery.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ua.edu.ukma.springers.voltstore.delivery.carrier.CarrierException;
import ua.edu.ukma.springers.voltstore.delivery.carrier.CarrierGateway;
import ua.edu.ukma.springers.voltstore.delivery.carrier.ConsignmentRequest;
import ua.edu.ukma.springers.voltstore.delivery.carrier.Parcel;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.PackagingUnavailableException;

/**
 * One TTN registration attempt, shared by the automatic job (Tier 1) and the worker's manual retry (Tier 3):
 * fetch the packaging from Inventory → calculate the parcel → register it with the carrier. It changes nothing in
 * the database and must be called outside a transaction (it makes remote calls); the caller applies the result.
 * <ul>
 *   <li>Inventory unavailable, timeout, {@code 5xx} → retryable {@code inventory_unavailable}.</li>
 *   <li>Inventory {@code 404} / {@code 422} → not retryable {@code stock_item_not_found:{productId}} /
 *   {@code packaging_data_missing:{productId}}.</li>
 *   <li>Parcel too large to calculate → not retryable {@code parcel_calculation_failed}.</li>
 *   <li>Carrier error or timeout → retryable {@code carrier_error: <message>}.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TtnRegistrationService {
    private final PackagingProvider packagingProvider;
    private final CarrierGateway carrierGateway;

    public TtnAttemptResult attempt(ShipmentSnapshot shipment) {
        Parcel parcel;
        try {
            parcel = ParcelCalculator.calculate(packagingProvider.packagingFor(shipment.items()));
        } catch (PackagingUnavailableException e) {
            return new TtnAttemptResult.Failure(e.getReason(), e.isRetryable());
        } catch (IllegalArgumentException e) {
            log.error("Parcel of order {} cannot be calculated: {}", shipment.orderId(), e.getMessage());
            return new TtnAttemptResult.Failure("parcel_calculation_failed", false);
        }
        try {
            String ttn = carrierGateway.register(new ConsignmentRequest(shipment.orderId(), shipment.carrier(),
                    shipment.recipient(), parcel, shipment.declaredValue(), shipment.currency()));
            return new TtnAttemptResult.Success(ttn, parcel);
        } catch (CarrierException e) {
            return new TtnAttemptResult.Failure("carrier_error: " + e.getMessage(), true);
        }
    }

    /** Best-effort clean-up of a TTN whose result could not be stored (the shipment changed meanwhile). */
    public void cancelOrphanedTtn(ShipmentSnapshot shipment, String ttn) {
        try {
            carrierGateway.cancel(ttn);
            log.warn("Order {} changed during the TTN attempt; the new TTN {} was cancelled at the carrier",
                    shipment.orderId(), ttn);
        } catch (CarrierException e) {
            log.error("Order {} changed during the TTN attempt, and its new TTN {} could not be cancelled at the "
                    + "carrier: {}; cancel it manually", shipment.orderId(), ttn, e.getMessage());
        }
    }
}
