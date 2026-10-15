package ua.edu.ukma.springers.voltstore.delivery.carrier;

/**
 * Inbound port: carrier → Delivery, implemented by Delivery's service layer (EP-DEL-04). The mock calls it in-process
 * after a handover; a future webhook adapter would call the same method.
 */
public interface CarrierEventHandler {
    /**
     * The carrier delivered the parcel with this TTN. Must be idempotent: a carrier may report the same delivery
     * more than once.
     */
    void onDelivered(String ttn);
}
