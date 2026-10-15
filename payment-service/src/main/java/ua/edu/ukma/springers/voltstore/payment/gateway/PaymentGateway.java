package ua.edu.ukma.springers.voltstore.payment.gateway;

import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentGatewayTimeoutException;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentGatewayUnavailableException;

/**
 * Port of the Anti-Corruption Layer: the payment provider as the rest of Payment Service sees it, in VoltStore's own
 * terms. Each provider gets an adapter implementing this interface; no provider type, status code or field name may
 * appear outside the adapter.
 * <p>
 * Every request carries an idempotency key. The provider processes a key once: a repeat with the same parameters
 * returns the stored result without charging again, which makes a retry after a lost answer safe.
 */
public interface PaymentGateway {

    /**
     * @throws PaymentGatewayTimeoutException     no answer in time; the card may have been charged
     * @throws PaymentGatewayUnavailableException provider unreachable or failing; the card may have been charged
     */
    GatewayResult charge(ChargeCommand command);

    /**
     * @throws PaymentGatewayTimeoutException     no answer in time; the refund may have been made
     * @throws PaymentGatewayUnavailableException provider unreachable or failing; the refund may have been made
     */
    GatewayResult refund(RefundCommand command);
}
