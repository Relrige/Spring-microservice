package ua.edu.ukma.springers.voltstore.order.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ua.edu.ukma.springers.voltstore.order.dto.PlaceOrderRequest;
import ua.edu.ukma.springers.voltstore.order.dto.PlacedOrderResponse;
import ua.edu.ukma.springers.voltstore.order.dto.ValidateCheckoutRequest;
import ua.edu.ukma.springers.voltstore.order.security.AuthenticatedUser;
import ua.edu.ukma.springers.voltstore.order.services.CheckoutCheckService;
import ua.edu.ukma.springers.voltstore.order.services.OrderPlacementService;

import java.net.URI;

/** Customer checkout endpoints. Roles are enforced by {@code SecurityConfig}; the caller is always a customer. */
@RestController
@RequiredArgsConstructor
public class OrderController {
    private final CheckoutCheckService checkoutCheck;
    private final OrderPlacementService placement;

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
}
