package ua.edu.ukma.springers.voltstore.order.clients;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import ua.edu.ukma.springers.voltstore.order.dto.InsufficientItem;
import ua.edu.ukma.springers.voltstore.order.ports.CatalogProduct;
import ua.edu.ukma.springers.voltstore.order.ports.Payments.PaymentCard;
import ua.edu.ukma.springers.voltstore.order.ports.StockReservations.ReservationItem;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The internal endpoints Order calls, as declarative HTTP interfaces, and their wire formats. Only the adapters in
 * this package use them; the rest of the service sees the {@code ports}.
 */
final class HttpClients {

    private HttpClients() {
    }

    interface CatalogHttpClient {
        // EP-CAT-09; unknown IDs are missing from the answer
        @GetExchange("/catalog/products/batch-get")
        List<CatalogProduct> batchGet(@RequestParam("ids") List<UUID> ids);
    }

    interface InventoryHttpClient {
        // EP-INV-06: 204 reserved, 409 already reserved for this order, 422 { insufficientItems }
        @PostExchange("/inventory/reservations")
        void reserve(@RequestBody ReserveRequest request);

        // EP-INV-07: 204, also when nothing is reserved
        @PostExchange("/inventory/reservations/{orderId}/release")
        void release(@PathVariable UUID orderId);
    }

    interface PaymentHttpClient {
        // EP-PAY-01
        @PostExchange("/payments/charge")
        PaymentResponse charge(@RequestBody ChargeRequest request);

        // EP-PAY-02
        @PostExchange("/payments/refund")
        PaymentResponse refund(@RequestBody RefundRequest request);
    }

    interface DeliveryHttpClient {
        // EP-DEL-01: 1-100 distinct IDs; an order without a shipment comes back with status null
        @GetExchange("/delivery/shipments")
        List<ShipmentResponse> shipments(@RequestParam("orderIds") List<UUID> orderIds);

        // EP-DEL-02: 204, also without a shipment (Delivery stores a tombstone)
        @DeleteExchange("/delivery/shipments/{orderId}")
        void cancel(@PathVariable UUID orderId);
    }

    record ReserveRequest(UUID orderId, List<ReservationItem> items) {
    }

    record InsufficientStockProblem(List<InsufficientItem> insufficientItems) {
    }

    // The card is printed masked by PaymentCard.toString(), so this record is safe to log
    record ChargeRequest(UUID orderId, BigDecimal amount, String currency, PaymentCard cardDetails) {
    }

    record RefundRequest(UUID orderId, BigDecimal amount, String currency) {
    }

    record PaymentResponse(UUID transactionId, String status) {
    }

    // Status as text, so a status added by Delivery later does not break the whole answer
    record ShipmentResponse(UUID orderId, String status, String ttn, String failureReason) {
    }
}
