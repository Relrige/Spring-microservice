# System / Background Scenarios

> Draft scenarios for system-initiated flows — events, background jobs, and cross-service reactions
> that are not directly triggered by a user action but are essential to system behaviour.

---

## SCN-S01: ProductCreated → StockItem Initialization (Async)

Triggered after SCN-CM01.

1. Catalog Service publishes event: `ProductCreated(productId)`.
2. Inventory Service consumes the event.
3. Inventory Service idempotently creates `StockItem(productId, availableQty=0, reservedQty=0, packaging=null)` if it does not already exist.

*Idempotent: if a StockItem was already created by a prior stock write (fallback case), no action is taken.*

---

## SCN-S02: Stock Depletion → Catalog Read-Model Update (Async)

Triggered when any operation causes `availableQty` to reach `0` for an `ACTIVE` product
(stock reservation, write-off, or adjustment).

1. Inventory Service publishes event: `ProductStockDepleted(productId)`.
2. Catalog Service consumes the event.
3. Catalog Service updates its read-model: `stockStatus = OUT_OF_STOCK` for `productId`.
4. Subsequent browsing and product detail responses reflect `OUT_OF_STOCK`.

---

## SCN-S03: Stock Replenishment → Catalog Read-Model Update (Async)

Triggered when `availableQty` increases from `0` to `> 0` for an `ACTIVE` product
(restocking, stock adjustment, or reservation release).

1. Inventory Service publishes event: `ProductStockReplenished(productId)`.
2. Catalog Service consumes the event.
3. Catalog Service updates its read-model: `stockStatus = IN_STOCK` for `productId`.

---

## SCN-S04: Automatic TTN Generation After Payment (Happy Path)

Triggered after SCN-C16 (payment success).

1. Order Service publishes event: `OrderPaid(orderId)`.
2. Delivery Service consumes `OrderPaid`.
3. Delivery Service fetches packaging dimensions from Inventory Service for each item in the order: `GET /inventory/stock/{productId}/packaging`.
4. Delivery Service calls the carrier gateway to register the consignment (orderId, recipientDetails, parcels).
5. Carrier gateway returns a TTN (tracking number).
6. Delivery Service stores the TTN and sets `deliveryStatus = TTN_GENERATED`.
7. The TTN is now visible in the fulfillment queue (SCN-INV04).

---

## SCN-S05: TTN Generation Failure → Three-Tier Resilience

Triggered when step 4 of SCN-S04 fails.

1. The carrier gateway returns an error or times out.
2. **Tier 1 — Automatic Retries:** Delivery Service retries automatically with exponential backoff (e.g., 3 attempts: 5s, 30s, 2min).
3. All retries fail.
4. **Tier 2 — TTN_FAILED Flag:** Delivery Service sets `deliveryStatus = TTN_FAILED`. Order remains in `PAID` / `PENDING_SHIPPING`. Failure reason is recorded.
5. The fulfillment queue displays the error and a "Retry" action for the worker.
6. **Tier 3 — Manual Retry:** Inventory Worker triggers SCN-INV05 once the carrier is available.

*A paid order is never automatically cancelled because of a carrier integration failure.*

---

## SCN-S06: Reservation TTL Expiry → Stock Release

Only applies to orders in `PENDING_PAYMENT`. Once an order transitions to `PAID`, the stock reservation is committed and can only be released through explicit cancellation (SCN-S07) — the TTL is no longer relevant.

1. Order Service detects that a `PENDING_PAYMENT` order's payment deadline has passed.
2. Order Service sets `Order.status = CANCELLED` (expired).
3. Order Service calls Inventory Service to release the reservation: for each reserved item, `reservedQty` is decremented and `availableQty` is restored.
4. If any item's `availableQty` transitions from `0` to `> 0` on an `ACTIVE` product → Inventory Service publishes `ProductStockReplenished(productId)` (triggers SCN-S03).
5. Order Service publishes event: `OrderCancelled(orderId)`.
6. Notification Service consumes `OrderCancelled` → sends "Order Expired — Payment Deadline Missed" email.

*How Order Service detects expiration is an implementation decision. Two candidate approaches:*
- *Scheduled polling job: Order Service periodically queries for `PENDING_PAYMENT` orders where `reservationExpiresAt < now()`.*
- *Delayed message via broker: at order creation time, a message is scheduled with the TTL duration; when it fires, Order Service processes the expiration.*

---

## SCN-S07: Order Cancellation Saga (Compensation Flow)

Triggered after SCN-C23 or SCN-C24 sets `Order.status = CANCELLATION_REQUESTED`.

1. **Inventory Service** releases the stock reservation for all order items: `reservedQty → availableQty`.
   - If any item transitions from `0` to `> 0` stock on an `ACTIVE` product → publishes `ProductStockReplenished`.
2. **Delivery Service** cancels the registered consignment / TTN with the carrier gateway (if a TTN was generated).
3. **Payment Service** calls the payment gateway to refund the charged amount.
   - Records `PaymentRecord(status=REFUNDED)`.
4. **Order Service** sets `Order.status = CANCELLED`.
5. Order Service publishes event: `OrderCancelled(orderId)`.
6. **Notification Service** consumes `OrderCancelled` → sends "Order Cancelled & Refund Initiated" email.

*Cancellation is only reachable while `status ∈ {PENDING_PAYMENT, PAID, PENDING_SHIPPING}`, so the parcel has never been handed to the carrier. Step 2 is a precaution for when a TTN was pre-generated.*

*Saga coordination approach (choreography vs. orchestration) is still an open decision — to be resolved after the state machine is defined.*

---

## SCN-S08: Carrier Webhook → Order DELIVERED

Triggered by a carrier delivery confirmation (simulated via webhook or background job for development purposes).

1. Carrier gateway sends delivery confirmation: `POST /delivery/carrier-webhook` with `{orderId, ttn, status: DELIVERED}`.
2. Delivery Service validates the webhook payload.
3. Delivery Service sets `deliveryStatus = DELIVERED`.
4. Delivery Service publishes event: `OrderDelivered(orderId, ttn)`.
5. Order Service consumes `OrderDelivered` → sets `Order.status = DELIVERED`.
6. Notification Service consumes `OrderDelivered` → sends "Parcel Delivered" email.

---

## SCN-S09: Notification Dispatch (Summary)

`Notification Service` is a pure event consumer. It reacts to the following domain events and sends emails:

| Event consumed | Email sent |
|----------------|-----------|
| `OrderPlaced` | "Your order has been placed" |
| `OrderPaid` | "Payment received — your order is being processed" |
| `OrderShipped` | "Your parcel is on its way (TTN: ...)" |
| `OrderDelivered` | "Your parcel has been delivered" |
| `OrderCancelled` | "Your order has been cancelled — refund has been initiated" |

*Error handling strategy for failed email delivery is deferred.*
