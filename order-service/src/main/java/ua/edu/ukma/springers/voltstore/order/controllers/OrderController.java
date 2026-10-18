package ua.edu.ukma.springers.voltstore.order.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.order.dto.CancellationResponse;
import ua.edu.ukma.springers.voltstore.order.dto.OrderDetailsResponse;
import ua.edu.ukma.springers.voltstore.order.dto.OrderSummaryResponse;
import ua.edu.ukma.springers.voltstore.order.dto.PaidOrderResponse;
import ua.edu.ukma.springers.voltstore.order.dto.PayOrderRequest;
import ua.edu.ukma.springers.voltstore.order.dto.PlaceOrderRequest;
import ua.edu.ukma.springers.voltstore.order.dto.PlacedOrderResponse;
import ua.edu.ukma.springers.voltstore.order.dto.ValidateCheckoutRequest;
import ua.edu.ukma.springers.voltstore.order.paging.PageResponse;
import ua.edu.ukma.springers.voltstore.order.paging.Paging;
import ua.edu.ukma.springers.voltstore.order.security.AuthenticatedUser;
import ua.edu.ukma.springers.voltstore.order.services.CheckoutCheckService;
import ua.edu.ukma.springers.voltstore.order.services.OrderCancellationService;
import ua.edu.ukma.springers.voltstore.order.services.OrderPaymentService;
import ua.edu.ukma.springers.voltstore.order.services.OrderPlacementService;
import ua.edu.ukma.springers.voltstore.order.services.OrderQueryService;

import java.net.URI;
import java.util.UUID;

/** Customer endpoints: checkout, payment, history and details. Roles are enforced by {@code SecurityConfig}; the caller is always a customer. */
@RestController
@RequiredArgsConstructor
public class OrderController {
    private final CheckoutCheckService checkoutCheck;
    private final OrderPlacementService placement;
    private final OrderPaymentService payment;
    private final OrderQueryService queries;
    private final OrderCancellationService cancellation;

    /**
     * EP-ORD-01: {@code 204} when every product is active and in stock, {@code 422 { ineligibleItems }} otherwise.
     * A soft check: Catalog's stock status says only "some" or "none", so asking for more units than are left still
     * passes here and is refused by the submission.
     */
    @PostMapping("/orders/validate")
    public ResponseEntity<Void> validate(@Valid @RequestBody ValidateCheckoutRequest request) {
        checkoutCheck.check(request.items());
        return ResponseEntity.noContent().build();
    }

    /** EP-ORD-02: places the order and reserves its stock; the customer then pays within the payment window. */
    @PostMapping("/orders")
    public ResponseEntity<PlacedOrderResponse> place(@Valid @RequestBody PlaceOrderRequest request,
                                                     @AuthenticationPrincipal AuthenticatedUser customer) {
        PlacedOrderResponse order = placement.place(customer, request);
        return ResponseEntity.created(URI.create("/orders/" + order.orderId())).body(order);
    }

    /**
     * EP-ORD-03: charges the order's total to the card. After a {@code 503} the customer may repeat the request with
     * the <b>same</b> card: Payment then returns the real outcome without charging twice.
     */
    @PostMapping("/orders/{orderId}/pay")
    public PaidOrderResponse pay(@PathVariable UUID orderId, @Valid @RequestBody PayOrderRequest request,
                                 @AuthenticationPrincipal AuthenticatedUser customer) {
        return payment.pay(orderId, customer, request.cardDetails());
    }

    /** EP-ORD-04: the caller's orders, newest first. */
    @GetMapping("/orders")
    public PageResponse<OrderSummaryResponse> history(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "" + Paging.DEFAULT_SIZE) int size,
                                                      @AuthenticationPrincipal AuthenticatedUser customer) {
        return queries.history(customer, page, size);
    }

    /**
     * EP-ORD-06: {@code 202} with {@code CANCELLED} when the saga finished, or {@code CANCELLATION_REQUESTED} when a
     * step failed and will be retried by the job; either way the cancellation is accepted.
     */
    @DeleteMapping("/orders/{orderId}")
    public ResponseEntity<CancellationResponse> cancel(@PathVariable UUID orderId,
                                                       @AuthenticationPrincipal AuthenticatedUser customer) {
        return ResponseEntity.accepted().body(cancellation.cancel(orderId, customer));
    }

    /** EP-ORD-05: one of the caller's orders, with its TTN and delivery status when Delivery has them. */
    @GetMapping("/orders/{orderId}")
    public OrderDetailsResponse details(@PathVariable UUID orderId,
                                        @AuthenticationPrincipal AuthenticatedUser customer) {
        return queries.details(orderId, customer);
    }
}
