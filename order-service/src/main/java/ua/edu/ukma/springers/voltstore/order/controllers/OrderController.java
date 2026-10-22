package ua.edu.ukma.springers.voltstore.order.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import ua.edu.ukma.springers.voltstore.order.openapi.IneligibleItemsProblem;
import ua.edu.ukma.springers.voltstore.order.openapi.InsufficientStockProblem;
import ua.edu.ukma.springers.voltstore.order.openapi.OrderStateProblem;
import ua.edu.ukma.springers.voltstore.order.openapi.OrderTotalTooLargeProblem;
import ua.edu.ukma.springers.voltstore.order.openapi.PaymentDeclinedProblem;
import ua.edu.ukma.springers.voltstore.order.openapi.Problem;
import ua.edu.ukma.springers.voltstore.order.openapi.ValidationProblem;
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

import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;
import static ua.edu.ukma.springers.voltstore.order.openapi.OpenApiConfig.BEARER_AUTH;

/** Customer endpoints: checkout, payment, history and details. Roles are enforced by {@code SecurityConfig}; the caller is always a customer. */
@Tag(name = "Orders")
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
    @Operation(summary = "Check a cart before checkout (EP-ORD-01)",
            description = "Role: CUSTOMER. A soft check against Catalog: every product active and in stock. Nothing is stored and no stock is locked.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "204", description = "Every product can be ordered")
    @ApiResponse(responseCode = "400", description = "Empty items, more than 100, a duplicate product or an invalid quantity",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "422", description = "Some products cannot be ordered",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = IneligibleItemsProblem.class)))
    @ApiResponse(responseCode = "503", description = "Catalog cannot be reached",
            headers = @Header(name = "Retry-After", description = "Seconds to wait before retrying",
                    schema = @Schema(type = "integer")),
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @PostMapping("/orders/validate")
    public ResponseEntity<Void> validate(@Valid @RequestBody ValidateCheckoutRequest request) {
        checkoutCheck.check(request.items());
        return ResponseEntity.noContent().build();
    }

    /** EP-ORD-02: places the order and reserves its stock; the customer then pays within the payment window. */
    @Operation(summary = "Place an order (EP-ORD-02)",
            description = "Role: CUSTOMER. Takes current prices from Catalog and reserves the stock; the order must then be paid before `reservationExpiresAt`.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "201", description = "Placed",
            headers = @Header(name = "Location", description = "`/orders/{orderId}`",
                    schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "Invalid items or delivery details",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated, or the token carries no email",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "422", description = "A product cannot be ordered, the stock is "
            + "insufficient, or the total is too large; no order was created. The `type` tells them apart.",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(oneOf = {IneligibleItemsProblem.class, InsufficientStockProblem.class,
                            OrderTotalTooLargeProblem.class})))
    @ApiResponse(responseCode = "503", description = "Catalog or Inventory cannot be reached; no order was created",
            headers = @Header(name = "Retry-After", description = "Seconds to wait before retrying",
                    schema = @Schema(type = "integer")),
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
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
    @Operation(summary = "Pay for an order (EP-ORD-03)",
            description = "Role: CUSTOMER. Charges the order total to the card. After a 503, repeating the request with the same card is safe: it is never charged twice.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "Paid")
    @ApiResponse(responseCode = "400", description = "Invalid card details",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "402", description = "The card was declined; the order can be paid with "
            + "another card",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = PaymentDeclinedProblem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "404", description = "No such order, or not the caller's",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "409", description = "The order is not awaiting payment (OrderStateProblem), "
            + "its payment window has passed, or a previous attempt is still in progress (Problem)",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(oneOf = {OrderStateProblem.class, Problem.class})))
    @ApiResponse(responseCode = "503", description = "Payment cannot be reached; retry with the same card",
            headers = @Header(name = "Retry-After", description = "Seconds to wait before retrying",
                    schema = @Schema(type = "integer")),
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @PostMapping("/orders/{orderId}/pay")
    public PaidOrderResponse pay(@PathVariable UUID orderId, @Valid @RequestBody PayOrderRequest request,
                                 @AuthenticationPrincipal AuthenticatedUser customer) {
        return payment.pay(orderId, customer, request.cardDetails());
    }

    /** EP-ORD-04: the caller's orders, newest first. */
    @Operation(summary = "View my order history (EP-ORD-04)",
            description = "Role: CUSTOMER. Newest first.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "One page of orders")
    @ApiResponse(responseCode = "400", description = "Invalid `page` or `size`",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ValidationProblem.class)))
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @GetMapping("/orders")
    public PageResponse<OrderSummaryResponse> history(@Parameter(description = "Zero-based page number")
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @Parameter(description = "Page size, at most " + Paging.MAX_SIZE)
                                                      @RequestParam(defaultValue = "" + Paging.DEFAULT_SIZE) int size,
                                                      @AuthenticationPrincipal AuthenticatedUser customer) {
        return queries.history(customer, page, size);
    }

    /**
     * EP-ORD-06: {@code 202} with {@code CANCELLED} when the saga finished, or {@code CANCELLATION_REQUESTED} when a
     * step failed and will be retried by the job; either way the cancellation is accepted.
     */
    @Operation(summary = "Cancel an order (EP-ORD-06)",
            description = "Role: CUSTOMER. Allowed in PENDING_PAYMENT, PAID and PENDING_SHIPPING; a paid order is refunded. `status` is CANCELLED when the cancellation finished, or CANCELLATION_REQUESTED when a step will be retried.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "202", description = "Cancellation accepted")
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "404", description = "No such order, or not the caller's",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "409", description = "The order cannot be cancelled in its status, or a "
            + "cancellation is already in progress",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = OrderStateProblem.class)))
    @DeleteMapping("/orders/{orderId}")
    public ResponseEntity<CancellationResponse> cancel(@PathVariable UUID orderId,
                                                       @AuthenticationPrincipal AuthenticatedUser customer) {
        return ResponseEntity.accepted().body(cancellation.cancel(orderId, customer));
    }

    /** EP-ORD-05: one of the caller's orders, with its TTN and delivery status when Delivery has them. */
    @Operation(summary = "View an order (EP-ORD-05)",
            description = "Role: CUSTOMER. `ttn` and `deliveryStatus` are missing while Delivery has no shipment or cannot be reached.",
            security = @SecurityRequirement(name = BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "The order")
    @ApiResponse(responseCode = "401", description = "Not authenticated",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "403", description = "Not a CUSTOMER",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @ApiResponse(responseCode = "404", description = "No such order, or not the caller's",
            content = @Content(mediaType = APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = Problem.class)))
    @GetMapping("/orders/{orderId}")
    public OrderDetailsResponse details(@PathVariable UUID orderId,
                                        @AuthenticationPrincipal AuthenticatedUser customer) {
        return queries.details(orderId, customer);
    }
}
