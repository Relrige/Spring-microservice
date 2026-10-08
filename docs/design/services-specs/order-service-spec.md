# Order Service — Implementation Spec

Owns the order lifecycle, immutable `OrderItemSnapshot` records (price-at-order-time), and coordinates the two-phase checkout flow. It is the central orchestrator for payment and the cancellation saga — it makes synchronous calls to Catalog, Inventory, and Payment services to execute multi-step workflows.

It also serves the **Inventory Worker's fulfillment queue**, aggregating order data with delivery statuses fetched from Delivery Service.

Guest orders are supported: orders without a registered account are identified by `guestEmail` and secured by an `orderAccessToken` for subsequent access.

---

## Service Overview

| | |
|---|---|
| **Owned entities** | `Order` (see order-state-machine.md for full field list and state diagram); `OrderItemSnapshot` (`id`, `productId`, `productName` (TBD), `unitPriceAtOrder`, `quantity`) |
| **Key enums** | `OrderStatus { PENDING_PAYMENT, PAID, PENDING_SHIPPING, SHIPPED, DELIVERED, CANCELLATION_REQUESTED, CANCELLED, EXPIRED }` |
| **Events emitted** | `OrderPlaced`, `OrderPaid`, `OrderShipped`, `OrderCancelled` |
| **Events consumed** | `TTNGenerated` (from Delivery Service), `OrderDelivered` (from Delivery Service) |
| **Also see** | [order-state-machine.md](../entities/order-state-machine.md) for the authoritative state diagram and transition table |

---

## EP-ORD-01: Checkout Soft Check (Validate Cart)

**Type:** `HTTP POST /orders/validate`
**Caller:** Customer (unauthenticated or `ROLE_CUSTOMER`, via API Gateway) — triggered when customer clicks "Proceed to Checkout"
**Auth:** None (works for both guests and registered customers)

**Steps:**
1. Parse request body: `items: [ { productId, quantity } ]`. Must be non-empty; all quantities ≥ 1.
2. Call Catalog Service to verify that all products are `ACTIVE`:
   - `GET /catalog/products/batch-get?ids={productIds}` — check the returned `status` field for each product.
   - Collect any products that are `NOT_ACTIVE` or not found.
3. Call Inventory Service to verify stock availability:
   - For each item, check that `availableQuantity >= requestedQuantity`.
   - Exact endpoint TBD (could be a batch availability check endpoint — `POST /inventory/stock/availability-check`, to be defined during implementation).
   - Collect any items with insufficient stock.
4. If any items are ineligible (inactive or insufficient stock) → return `422 Unprocessable Entity { ineligibleItems: [ { productId, reason: "NOT_ACTIVE" | "INSUFFICIENT_STOCK" } ] }`.
5. All checks pass → return `200 OK`. No stock is locked.

> This is a **soft check only** — no state changes are made. Between this check and the actual order submission, items may become unavailable again. That is handled by EP-ORD-02.

**Edge cases:**
- `items` array empty → `400 Bad Request`
- Any `quantity` < 1 → `400 Bad Request`
- Catalog Service unavailable → `503 Service Unavailable`
- Inventory Service unavailable → `503 Service Unavailable`

**External calls:**
- Catalog Service: `GET /catalog/products/batch-get` — check product status — **sync**
- Inventory Service: batch availability check endpoint — **sync**

**Emits:** none
**Refs:** US-CUST-08, US-CUST-09, SCN-C11, SCN-C12

---

## EP-ORD-02: Submit Order & Hard Stock Reservation

**Type:** `HTTP POST /orders`
**Caller:** Customer (unauthenticated or `ROLE_CUSTOMER`, via API Gateway) — triggered on checkout form submission ("Submit & Pay")
**Auth:** None (supports both guests and registered customers)

**Steps:**
1. Parse and validate request body:
   - `items: [ { productId, quantity } ]` — non-empty, all quantities ≥ 1.
   - `deliveryDetails: { name, phone, address, carrier }` — all required fields non-empty.
   - For registered customers: `customerId` extracted from `X-User-Id` header.
   - For guests: `contactEmail` (non-empty, valid format) in the body; no JWT.
2. Call Catalog Service to fetch current prices **and** status for all items:
   - `GET /catalog/products/batch-get?ids={productIds}`.
   - If any product is `NOT_ACTIVE` or not found → abort; return `422 Unprocessable Entity { ineligibleItems }`.
   - Client-provided prices are **never trusted** — always use the price returned here for snapshots.
3. Call Inventory Service to atomically reserve stock:
   - `POST /inventory/reservations { orderId (pre-generated), items }`.
   - If any item has insufficient stock → abort; return `422 Unprocessable Entity { insufficientItems }`. No order is created.
4. Create the `Order` record in a single database transaction:
   - `status = PENDING_PAYMENT`
   - `customerId` (or null for guests) and `guestEmail`
   - `orderAccessToken = secureRandom()` (always generated; for registered customers it allows alternative access if needed)
   - `reservationExpiresAt = now() + 15 minutes`
   - `totalAmount = sum(unitPriceAtOrder × quantity)` computed from Catalog-returned prices
   - `items = [ OrderItemSnapshot(productId, unitPriceAtOrder, quantity) ]` — snapshot is immutable from this point
   - `deliveryDetails`
5. Publish event `OrderPlaced { orderId, customerEmail, totalAmount, currency }`.
6. Return `201 Created { orderId, orderAccessToken }`.
   - For guests, this `orderAccessToken` is also sent to `contactEmail` via Notification Service as a secret URL.

**Edge cases:**
- Any `quantity` < 1 → `400 Bad Request`
- `deliveryDetails` fields missing → `400 Bad Request`
- Guest order with missing or invalid `contactEmail` → `400 Bad Request`
- Any product `NOT_ACTIVE` or not found in Catalog → `422 Unprocessable Entity`
- Any item has insufficient stock (Inventory rejects reservation) → `422 Unprocessable Entity`
- Catalog Service or Inventory Service unavailable → `503 Service Unavailable`; no order created, no stock locked

**External calls:**
- Catalog Service: `GET /catalog/products/batch-get` — fetch prices + status for all items — **sync**
- Inventory Service: `POST /inventory/reservations` — atomically reserve stock — **sync**

**Emits:** `OrderPlaced { orderId, customerEmail, totalAmount, currency }` → consumed by Notification Service (EP-NOTIF-EVT-01)
**Refs:** US-CUST-08, US-CUST-09, US-CUST-07, SCN-C14, SCN-C15, SCN-C18, N-06

---

## EP-ORD-03: Initiate Card Payment

**Type:** `HTTP POST /orders/{orderId}/pay`
**Caller:** Customer (unauthenticated or `ROLE_CUSTOMER`, via API Gateway)
**Auth:** None (guest access by `?token=` or JWT for registered)

**Steps:**
1. Look up `Order` by `orderId`. If not found → `404 Not Found`.
2. Verify access: if registered, `Order.customerId` must match `X-User-Id`. If guest, `?token` must match `Order.orderAccessToken`. If neither → `403 Forbidden`.
3. Validate order state:
   - If `Order.status != PENDING_PAYMENT` → `409 Conflict` ("Order is not awaiting payment").
   - If `Order.reservationExpiresAt <= now()` → `409 Conflict` ("Payment window expired — order has expired"). Do **not** attempt the charge.
4. Call Payment Service to charge: `POST /payments/charge { orderId, amount: Order.totalAmount, currency, cardDetails }`.
5. **On Payment Service success (`200 OK`):**
   - Set `Order.status = PAID`.
   - Publish event `OrderPaid { orderId, customerId, customerEmail }`.
   - Return `200 OK`.
6. **On Payment Service failure (`402`):**
   - `Order.status` remains `PENDING_PAYMENT`. Stock reservation TTL is **not** reset.
   - Return `402 Payment Required { reason }` to the customer so they can retry with a different card.
7. **On Payment Service timeout / `5xx`:**
   - Do not change order status. Return `503 Service Unavailable`. Customer may retry.

**Edge cases:**
- `orderId` not found → `404 Not Found`
- Access denied (wrong customer / invalid guest token) → `403 Forbidden`
- Order not in `PENDING_PAYMENT` state → `409 Conflict`
- Reservation TTL already elapsed → `409 Conflict` ("Payment window expired")
- Card declined → `402 Payment Required`
- Payment gateway timeout → `503 Service Unavailable`

**External calls:**
- Payment Service: `POST /payments/charge` — **sync**

**Emits:** `OrderPaid { orderId, customerId (nullable), customerEmail }` → consumed by Customer Service (EP-CUST-EVT-01), Delivery Service (EP-DEL-EVT-01), Notification Service (EP-NOTIF-EVT-02)
**Refs:** US-CUST-10, US-CUST-11, SCN-C16, SCN-C17

---

## EP-ORD-04: View Order History (Registered Customer)

**Type:** `HTTP GET /orders`
**Caller:** Registered Customer (via API Gateway)
**Auth:** `ROLE_CUSTOMER`

**Steps:**
1. Extract `customerId` from `X-User-Id` header.
2. Query `Order` records by `customerId`, ordered by `placedAt DESC`, paginated.
3. Return `200 OK { content: [ { orderId, status, totalAmount, currency, placedAt } ], page, totalElements }`.

**Edge cases:**
- No orders found for the customer → return `200 OK []`

**External calls:** none
**Emits:** none
**Refs:** US-CUST-14, SCN-C20

---

## EP-ORD-05: View Order Details

**Type:** `HTTP GET /orders/{orderId}`
**Caller:** Registered Customer (JWT) or Guest (via `?token=orderAccessToken`)
**Auth:** `ROLE_CUSTOMER` (JWT) or none with valid `?token`

**Steps:**
1. Look up `Order` by `orderId`. If not found → `404 Not Found`.
2. Verify access:
   - If JWT present: `Order.customerId` must match `X-User-Id`. If mismatch → `403 Forbidden`.
   - If no JWT: `?token` query param must match `Order.orderAccessToken`. If mismatch or missing → `403 Forbidden`.
3. Fetch delivery status from Delivery Service: `GET /delivery/shipments?orderIds={orderId}` (single item).
4. Return `200 OK` with full order details: `{ orderId, status, items: [ { productId, unitPriceAtOrder, quantity } ], deliveryDetails, totalAmount, currency, placedAt, ttn (if available), deliveryStatus }`.

**Edge cases:**
- `orderId` not found → `404 Not Found`
- Access denied → `403 Forbidden`
- Delivery Service unavailable → return order without delivery status (degrade gracefully; `ttn` and `deliveryStatus` as null)

**External calls:**
- Delivery Service: `GET /delivery/shipments?orderIds={orderId}` — fetch TTN and delivery status — **sync**

**Emits:** none
**Refs:** US-CUST-15, US-CUST-16, US-CUST-07, SCN-C21, SCN-C22, SCN-C19

---

## EP-ORD-06: Request Order Cancellation

**Type:** `HTTP DELETE /orders/{orderId}`
**Caller:** Registered Customer (JWT) or Guest (via `?token=orderAccessToken`)
**Auth:** `ROLE_CUSTOMER` (JWT) or none with valid `?token`

**Steps:**
1. Look up `Order` by `orderId`. If not found → `404 Not Found`.
2. Verify access (same logic as EP-ORD-05 step 2).
3. Validate that `Order.status` is in `{ PENDING_PAYMENT, PAID, PENDING_SHIPPING }`. If not (e.g., `SHIPPED`, `DELIVERED`, `CANCELLATION_REQUESTED`, `CANCELLED`, `EXPIRED`) → `409 Conflict` ("Order cannot be cancelled in its current state").
4. **For registered customers:** set `Order.status = CANCELLATION_REQUESTED` immediately and proceed to execute the cancellation saga (step 6).
5. **For guests:** dispatch a one-time cancellation verification link/code to `Order.guestEmail`. Return `202 Accepted` ("Verification email sent — confirm cancellation via the link"). Do **not** set status yet. Guest must confirm via the verification link (separate flow — deferred, N-11).
6. **Cancellation saga execution** (for registered customers, or after guest confirms):
   - Call Inventory Service: `POST /inventory/reservations/{orderId}/release` — release reserved stock. **sync**
   - Call Delivery Service: `DELETE /delivery/shipments/{orderId}` — cancel TTN if one was generated. **sync**
   - If `Order.status` at cancellation time was `PAID` or `PENDING_SHIPPING` — call Payment Service: `POST /payments/refund { orderId, amount: Order.totalAmount, currency }`. **sync**
   - Set `Order.status = CANCELLED`.
   - Publish event `OrderCancelled { orderId, customerEmail, reason: "CUSTOMER_CANCELLED" }`.
7. Return `202 Accepted` (for registered customers, indicates saga has started or completed).

> Saga coordination approach (choreography vs. orchestration) is an **open architectural decision** (O3 in review notes). The steps above describe an orchestrated approach where Order Service drives all saga steps synchronously. This may change.

**Edge cases:**
- `orderId` not found → `404 Not Found`
- Access denied → `403 Forbidden`
- `Order.status` not in cancellable set → `409 Conflict`
- `Order.status == CANCELLATION_REQUESTED` → `409 Conflict` ("Cancellation already in progress")
- Inventory Service fails during saga → saga error handling TBD (retry / compensate / alert)
- Payment refund fails during saga → saga error handling TBD

**External calls:**
- Inventory Service: `POST /inventory/reservations/{orderId}/release` — **sync**
- Delivery Service: `DELETE /delivery/shipments/{orderId}` — **sync**
- Payment Service: `POST /payments/refund` — **sync** (only if payment was made)

**Emits:** `OrderCancelled { orderId, customerEmail, reason: "CUSTOMER_CANCELLED" }` → consumed by Notification Service (EP-NOTIF-EVT-05)
**Refs:** US-CUST-13, SCN-C23, SCN-C24, SCN-S07, N-09, N-11

---

## EP-ORD-07: View Fulfillment Queue (Inventory Worker)

**Type:** `HTTP GET /orders/fulfillment-queue`
**Caller:** Inventory Worker (via API Gateway)
**Auth:** `ROLE_INVENTORY_WORKER`

**Steps:**
1. Query all `Order` records with `status` in `{ PAID, PENDING_SHIPPING }`.
2. Extract all `orderId` values from the result.
3. Call Delivery Service in a **single batch request**: `GET /delivery/shipments?orderIds={id1,id2,...}`.
4. Merge the delivery status data onto each order entry: `{ orderId, status, placedAt, items (from OrderItemSnapshot), deliveryDetails, deliveryStatus, ttn, failureReason }`.
5. Return `200 OK [ merged order list ]`.

> Delivery status (`TTN_GENERATED`, `TTN_FAILED`, etc.) is **not** stored on the `Order` entity — it lives exclusively in Delivery Service. This endpoint fetches it on demand via a single sync batch call to avoid data duplication.

**Edge cases:**
- No orders in `PAID` / `PENDING_SHIPPING` state → return `200 OK []`
- Delivery Service unavailable → return orders without delivery status (degrade gracefully; mark `deliveryStatus` as unavailable)

**External calls:**
- Delivery Service: `GET /delivery/shipments?orderIds=...` — batch fetch delivery statuses — **sync**

**Emits:** none
**Refs:** US-INV-05, SCN-INV04

---

## EP-ORD-08: Mark Order as Shipped (Inventory Worker Dispatch)

**Type:** `HTTP POST /orders/{orderId}/ship`
**Caller:** Inventory Worker (via API Gateway)
**Auth:** `ROLE_INVENTORY_WORKER`

**Steps:**
1. Look up `Order` by `orderId`. If not found → `404 Not Found`.
2. Validate `Order.status` is in `{ PAID, PENDING_SHIPPING }`. If not → `409 Conflict`.
3. Verify that a TTN has been generated: call Delivery Service `GET /delivery/shipments?orderIds={orderId}` and check that `deliveryStatus == TTN_GENERATED`. If TTN is not yet generated (`TTN_PENDING` or `TTN_FAILED`) → `409 Conflict` ("TTN must be generated before dispatching").
4. Set `Order.status = SHIPPED`.
5. Publish event `OrderShipped { orderId, ttn }`.
6. Return `200 OK`.

**Edge cases:**
- `orderId` not found → `404 Not Found`
- `Order.status` not in `{ PAID, PENDING_SHIPPING }` → `409 Conflict`
- TTN not yet generated → `409 Conflict` (worker must resolve TTN failure first via EP-DEL-03)
- Delivery Service unavailable when checking TTN → `503 Service Unavailable`

**External calls:**
- Delivery Service: `GET /delivery/shipments?orderIds={orderId}` — verify TTN generated — **sync**

**Emits:** `OrderShipped { orderId, ttn }` → consumed by Inventory Service (EP-INV-EVT-02) and Notification Service (EP-NOTIF-EVT-03)
**Refs:** US-INV-07, SCN-INV06, inventory-worker-technical-insights §4, N-09

---

## EP-ORD-EVT-01: Consume `TTNGenerated`

**Type:** Event consumer
**Producer:** Delivery Service
**Trigger:** Delivery Service successfully registers a TTN with the carrier after payment.

**Payload received:** `{ orderId, ttn }`

**Steps:**
1. Receive and deserialise the `TTNGenerated` event.
2. Look up `Order` by `orderId`. If not found → log warning and acknowledge.
3. If `Order.status != PAID` → log warning and acknowledge (unexpected state — skip transition).
4. Set `Order.status = PENDING_SHIPPING`.
5. Acknowledge the message.

**Edge cases:**
- `orderId` not found → log and acknowledge (defensive)
- Order not in `PAID` state → skip and acknowledge (idempotent)
- Duplicate event delivery → second transition attempt finds order already in `PENDING_SHIPPING`; idempotent acknowledge

**External calls:** none
**Refs:** order-state-machine.md (PAID → PENDING_SHIPPING transition), SCN-S04

---

## EP-ORD-EVT-02: Consume `OrderDelivered`

**Type:** Event consumer
**Producer:** Delivery Service
**Trigger:** Carrier confirms delivery via webhook; Delivery Service processes and publishes this event.

**Payload received:** `{ orderId, ttn }`

**Steps:**
1. Receive and deserialise the `OrderDelivered` event.
2. Look up `Order` by `orderId`. If not found → log warning and acknowledge.
3. If `Order.status != SHIPPED` → log warning and acknowledge (unexpected — skip).
4. Set `Order.status = DELIVERED`.
5. Acknowledge the message.

**Edge cases:**
- `orderId` not found → log and acknowledge
- Order not in `SHIPPED` state → log and acknowledge (idempotent)

**External calls:** none
**Refs:** order-state-machine.md (SHIPPED → DELIVERED transition), SCN-S08 (step 5)

---

## EP-ORD-JOB-01: Reservation TTL Expiry Detection

**Type:** Background job
**Trigger:** Scheduled polling (e.g., every 1 minute) **or** delayed broker message scheduled at order creation time — implementation approach TBD (see N-06, SCN-S06 notes).

**Steps:**
1. Detect all `Order` records where `status = PENDING_PAYMENT` AND `reservationExpiresAt <= now()`.
2. For each expired order:
   a. Set `Order.status = EXPIRED`.
   b. Call Inventory Service to release the reservation: `POST /inventory/reservations/{orderId}/release`. **sync**
   c. Publish event `OrderCancelled { orderId, customerEmail, reason: "EXPIRED" }`.
3. Continue to the next expired order.

> `EXPIRED` is a **system-initiated terminal state**; it does **not** go through `CANCELLATION_REQUESTED`. It indicates no payment was made and no refund is owed.

**Edge cases:**
- Inventory Service call fails for a specific order → log the error; leave that order in `PENDING_PAYMENT` to be picked up in the next job run (retry on next cycle)
- Order transitions to `PAID` concurrently with TTL check → the expiry job must guard against a race condition: check and set `EXPIRED` atomically using an optimistic lock or a conditional update (`WHERE status = PENDING_PAYMENT AND reservationExpiresAt <= now()`)
- Job runs multiple times for the same expired order → idempotent: if `Order.status` is already `EXPIRED`, skip

**External calls:**
- Inventory Service: `POST /inventory/reservations/{orderId}/release` — **sync** (per expired order)

**Emits:** `OrderCancelled { orderId, customerEmail, reason: "EXPIRED" }` → consumed by Notification Service (EP-NOTIF-EVT-05)
**Refs:** SCN-S06, N-06, order-state-machine.md
