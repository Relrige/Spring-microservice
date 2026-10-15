package ua.edu.ukma.springers.voltstore.delivery.carrier;

/**
 * Outbound port: Delivery → carrier. Only adapters of this port know a carrier's protocol and formats; the rest of
 * Delivery uses VoltStore's own types. Today the only adapter is the in-process {@link MockCarrierGateway}; a real
 * carrier or an out-of-process simulator would be another adapter, and nothing outside this package would change.
 */
public interface CarrierGateway {
    /**
     * Registers the parcel and returns the carrier's waybill number (TTN).
     *
     * @throws CarrierException if the carrier rejects the request or cannot be reached
     */
    String register(ConsignmentRequest request);

    /**
     * Cancels a registered TTN that has not been handed over yet.
     *
     * @throws CarrierException if the carrier rejects the request or cannot be reached
     */
    void cancel(String ttn);

    /** The courier has collected the parcel. Idempotent: a second call for the same TTN changes nothing. */
    void handOver(String ttn);
}
