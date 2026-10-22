# Order Service — Implementation Spec

Owns the order lifecycle, immutable `OrderItemSnapshot` records (price at order time), and coordinates the two-phase checkout flow. It is the central orchestrator for payment and the cancellation saga — it makes synchronous calls to Catalog, Inventory, Payment and Delivery to execute multi-step workflows.

It also serves the **Inventory Worker's fulfillment queue**, aggregating order data with delivery statuses fetched from Delivery Service.

**Only registered customers can order** (decision 2026-10-16). Every customer endpoint requires a logged-in user with role `CUSTOMER`; the customer is always identified by the gateway-supplied `X-User-Id`, never by a value in the path or body. There is no guest checkout: no `guestEmail`, no `orderAccessToken`, no `?token=` access and no emailed cancellation link. An order always has a `customerId`, and a customer can see and change only their own orders.

> **Revision note (2026-10-16, before implementation).** Rewritten after reading the implemented Catalog, Inventory, Payment, Delivery and Customer specs, so that every call and event matches what those services actually do. Differences from the first version are listed under *Changes from the First Spec Version*; the three design questions found on the way are settled under *Decisions*.

---

## Service Overview

| | |
|---|---|
| **Owned entities** | `Order` (`id`, `customerId`, `customerEmail`, `status`, `totalAmount`, `currency`, `deliveryDetails { name, phone, address, carrier }`, `reservationExpiresAt`, `placedAt`, `paidAt`, `statusBeforeCancellation`, `version`, and for interrupted cancellations `cancellationAttempts`, `cancellationFailure`, `cancellationLastAttemptAt`, `needsManualAttention`); `OrderItemSnapshot` (`id`, `productId`, `productTitle`, `unitPriceAtOrder`, `quantity`) |
| **Key enums** | `OrderStatus { PENDING_PAYMENT, PAID, PENDING_SHIPPING, SHIPPED, DELIVERED, CANCELLATION_REQUESTED, CANCELLED, EXPIRED }` |
| **Events emitted** | `OrderPlaced`, `OrderPaid`, `OrderShipped`, `OrderCancelled` on topic `order.order-events` (see *Order Events*) |
| **Events consumed** | `TTNGenerated`, `OrderDelivered` (from Delivery Service, topic `delivery.shipment-events`) |
| **Synchronous dependencies** | Catalog `GET /catalog/products/batch-get` (status, price, `stockStatus`); Inventory `POST /inventory/reservations`, `POST /inventory/reservations/{orderId}/release`; Payment `POST /payments/charge`, `POST /payments/refund`; Delivery `GET /delivery/shipments`, `DELETE /delivery/shipments/{orderId}` |
| **Callers** | Customers and Inventory Workers via the API Gateway; no internal (service-to-service) routes |
| **Technical tables** (not part of the domain model) | `outbox_events` (transactional outbox for order events); `reservation_attempts` and `pending_refunds` (work owed to Inventory and Payment, finished by EP-ORD-JOB-03/04); `idempotency_keys` (submit requests by `Idempotency-Key`, EP-ORD-02; purged by EP-ORD-JOB-05) |
| **Also see** | [order-state-machine.md](../entities/order-state-machine.md) for the authoritative state diagram and transition table |

---

## Cross-cutting Conventions

### Security and routing

Authentication follows the [Security Model](../services-requirements/security-model.md): the API Gateway validates the JWT and forwards `X-User-Id` / `X-User-Role`; the service trusts these headers. Every route has an explicit rule (method + path); anything else is denied (`denyAll`). The role check runs **before** the request body is parsed or validated.

| Route | Role | Routed by the gateway |
|---|---|---|
| `POST /orders/validate` | `CUSTOMER` | yes |
| `POST /orders` | `CUSTOMER` | yes |
| `POST /orders/{orderId}/pay` | `CUSTOMER` | yes |
| `GET /orders` | `CUSTOMER` | yes |
| `GET /orders/{orderId}` | `CUSTOMER` | yes |
| `DELETE /orders/{orderId}` | `CUSTOMER` | yes |
| `GET /orders/fulfillment-queue` | `INVENTORY_WORKER` | yes |
| `POST /orders/{orderId}/ship` | `INVENTORY_WORKER` | yes |

- Missing or invalid identity headers → `401`; wrong role → `403`. Roles are not hierarchical: `ADMIN` gets `403` on customer and worker routes.
- **Ownership.** On every route with `{orderId}` used by a customer, the order must belong to the caller (`Order.customerId == X-User-Id`). An order of another customer is answered **`404 Not Found`**, exactly like an order that does not exist, so customers cannot probe for other customers' order IDs.
- `/actuator/health/**` is open for the probes.
- **Outgoing calls** to Catalog, Inventory, Payment and Delivery carry `X-Internal-Token` (shared `INTERNAL_TOKEN`) and `X-Correlation-Id`; user headers are never forwarded. A request's `X-Correlation-Id` is kept in the logging context (a new ID is generated when missing).

### Responses and errors

- Errors use the shared RFC 9457 `ProblemDetail` shape: `type`, `title`, `status`, `detail`, `timestamp`, `service`. `type` is `https://voltstore.com/errors/{validation | not-found | conflict | ineligible-items | insufficient-stock | order-total-too-large | idempotency-key-mismatch | request-in-progress | payment-declined | payment-window-expired | service-unavailable | unauthorized | forbidden | internal-error}`.
- `400` = malformed request (invalid JSON, a field rule broken, a non-UUID path variable) with a field-level `errors` map. `422` = a well-formed request that cannot be done in the current state. Unexpected errors → `500` with a generic message; the stack trace is only logged.
- A dependency that is down or too slow → `503 Service Unavailable` with a fixed `detail` and `Retry-After`; hosts, upstream statuses and causes are only logged.
- Card data (EP-ORD-03) is never logged or echoed in an error, exactly as in Payment Service (see its *Card Data* section).
- **OpenAPI:** the API is also described by an OpenAPI document generated from the code, `order-service/openapi/public.json` ([OpenAPI Conventions](../services-requirements/openapi-conventions.md)); its error responses reference the named problem schemas listed there.

| Error | Status | Extra property |
|---|---|---|
| Order not found, or belongs to another customer | `404` | — |
| Product not active or not found (EP-ORD-01/02) | `422` (type `ineligible-items`) | `ineligibleItems: [ { productId, reason } ]` |
| Not enough stock to reserve (EP-ORD-02) | `422` (type `insufficient-stock`) | `insufficientItems: [ { productId, requested, available } ]` (passed through from Inventory) |
| Total above what Payment can charge at once (EP-ORD-02) | `422` (type `order-total-too-large`) | `maxTotal` |
| `Idempotency-Key` already used with a different request body (EP-ORD-02, D8) | `422` (type `idempotency-key-mismatch`) | — |
| A request with the same `Idempotency-Key` is still running (EP-ORD-02, D8) | `409` (type `request-in-progress`) | header `Retry-After: 1` |
| Order not in a state that allows the action | `409` | — |
| Payment window elapsed (EP-ORD-03) | `409` (type `payment-window-expired`) | — |
| Previous payment attempt still unresolved (EP-ORD-03) | `409` | — |
| Card declined (EP-ORD-03) | `402` (type `payment-declined`) | `reason` (passed through from Payment) |
| TTN not generated yet (EP-ORD-08) | `409` | — |
| Catalog, Inventory, Payment or Delivery unavailable | `503` | header `Retry-After` |

### Request limits

Item lists (EP-ORD-01/02) have 1–100 entries, each with a UUID `productId` and a `quantity` from 1 to 10 (the cart's own limit per product, INV-CART-02, so the API cannot be used to order more than the cart allows), and a `productId` may appear **only once** (rejected with `400`, not merged). These are the same limits as Catalog's batch-get (at most 100 IDs) and Inventory's reservation (at most 100 items, no duplicates), so a valid order request is always a valid downstream request.

### Concurrency and consistency

- **Optimistic locking** (`version` on `Order`): every status change is a check-and-set from an expected status. Two concurrent changes of one order (pay vs. cancel, pay vs. expiry, `TTNGenerated` vs. cancel) cannot both win; the loser re-reads the order and answers from its new status.
- **No database transaction is held during a remote call.** Remote calls run first; the status change and its outbox event are written afterwards in one short transaction.
- **Transactional outbox** for all four order events, as in Catalog and Inventory: the status change and the event are committed atomically, and a relay publishes them (at-least-once). Consumers must be idempotent.

### Order Events

| | |
|---|---|
| **Topic** | `order.order-events` (owned and declared by Order; name from `ORDER_EVENTS_TOPIC`), 3 partitions |
| **Key** | `orderId` — all events of one order are in one partition and keep their order |
| **Headers** | `eventType` (`OrderPlaced`, `OrderPaid`, `OrderShipped`, `OrderCancelled`) and `eventId`; consumers dispatch on `eventType` and ignore other types |
| **Payload** | JSON without Java type headers; every payload has `eventId` (UUID) and `timestamp` (ISO-8601 UTC) in addition to the fields below |
| **Consumers** | Inventory (`OrderShipped`), Delivery (`OrderPaid`, `OrderShipped`), Customer (`OrderPaid`), Notification (all four) |

Payloads (exact field lists also in the [Event Catalogue](../services-requirements/events-catalogue.md)):

- `OrderPlaced { orderId, customerId, customerEmail, totalAmount, currency, reservationExpiresAt }`
- `OrderPaid { orderId, customerId, customerEmail, deliveryDetails { name, phone, address, carrier }, items [ { productId, quantity } ], totalAmount, currency, paidAt }` — a fat event: Delivery creates the shipment from it without calling Order back. `customerId` is never null.
- `OrderShipped { orderId, ttn, customerEmail }`
- `OrderCancelled { orderId, customerId, customerEmail, reason: "CUSTOMER_CANCELLED" | "EXPIRED" }`

### Customer email

Notification Service sends every order email to `customerEmail`, which travels in the order events. Order Service stores the email on the order when it is placed (a snapshot, like the prices), so later events of that order use the same address.

The email comes from the **`X-User-Email`** header (decision D1): Auth Service puts the account's `email` into the JWT, and the API Gateway forwards it like `X-User-Id` and `X-User-Role` (and strips any client-supplied copy). The customer never types it, so it is always the account's address. A `CUSTOMER` request to `POST /orders` without a valid `X-User-Email` is a broken gateway contract → `401`, logged.

### Configuration

| Variable | Meaning |
|---|---|
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | Order's own PostgreSQL database (database per service) |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Kafka broker |
| `ORDER_EVENTS_TOPIC` | Topic of Order's events (default `order.order-events`) |
| `DELIVERY_EVENTS_TOPIC` | Delivery's topic with `TTNGenerated` / `OrderDelivered` (default `delivery.shipment-events`) |
| `INTERNAL_TOKEN` | Shared secret sent as `X-Internal-Token` on outgoing calls |
| `CATALOG_SERVICE_URL`, `INVENTORY_SERVICE_URL`, `PAYMENT_SERVICE_URL`, `DELIVERY_SERVICE_URL` | Base URLs of the dependencies |
| `PAYMENT_READ_TIMEOUT` | Read timeout of the Payment call; **at least 6 s** (Payment's worst case with its own timeout and repeat is about 4.5 s) |
| `ORDER_PAYMENT_WINDOW` | Payment window after placement (default `15m`) |
| `ORDER_CARRIERS` | Carriers a customer may choose at checkout, comma-separated (default `NOVA_POSHTA`); Delivery must support each |
| `ORDER_CANCELLATION_RETRY_INTERVAL` | How often EP-ORD-JOB-02 looks for interrupted cancellations (default `1m`) |
| `ORDER_RECOVERY_INTERVAL` | How often EP-ORD-JOB-03, EP-ORD-JOB-04 and EP-ORD-JOB-05 run (default `1m`) |
| `ORDER_IDEMPOTENCY_KEY_TTL` | How long a completed `Idempotency-Key` is remembered (default `24h`; EP-ORD-JOB-05, D10) |
| `ORDER_EXPIRY_INTERVAL`, `ORDER_EXPIRY_GRACE` | How often EP-ORD-JOB-01 runs (default `1m`) and how long after the window it waits (default `30s`; must be longer than `PAYMENT_READ_TIMEOUT`, checked at startup) |

---

## EP-ORD-01: Checkout Soft Check (Validate Cart)

**Type:** `HTTP POST /orders/validate`
**Caller:** Customer (via API Gateway) — triggered when the customer clicks "Proceed to Checkout"
**Auth:** `CUSTOMER`

**Request:** `{ items: [ { productId, quantity } ] }`

**Steps:**
1. Validate the request (see *Request limits*) → `400`.
2. Call Catalog `GET /catalog/products/batch-get?ids=…` (one call). A product that is missing from the answer or not `ACTIVE` is ineligible with reason `NOT_ACTIVE`.
3. Check stock with the same answer: a product whose `stockStatus` is `OUT_OF_STOCK` is ineligible with reason `INSUFFICIENT_STOCK` (decision D2). `stockStatus` is Catalog's read model of Inventory's stock events, so it says only "some stock" or "none": a request for more units than are left still passes here and is caught by the reservation in EP-ORD-02 (`422`).
4. Any ineligible items → `422 { ineligibleItems: [ { productId, reason: "NOT_ACTIVE" | "INSUFFICIENT_STOCK" } ] }`, listing all of them.
5. All checks pass → `204 No Content`. Nothing is stored and no stock is locked.

> This is a **soft check only**. Items may become unavailable before submission; EP-ORD-02 checks again and reserves.

**Edge cases:**
- Empty `items`, more than 100 items, a duplicate `productId`, `quantity < 1` → `400`
- Catalog unavailable → `503`; the check never reports "all fine" when it could not check

**External calls:** Catalog `GET /catalog/products/batch-get` (status **and** `stockStatus` in one call) — **sync**
**Emits:** none
**Refs:** US-CUST-08, US-CUST-09, SCN-C11, SCN-C12

---

## EP-ORD-02: Submit Order & Hard Stock Reservation

**Type:** `HTTP POST /orders`
**Caller:** Customer (via API Gateway) — checkout form submission ("Submit & Pay")
**Auth:** `CUSTOMER`

**Request:** `{ items: [ { productId, quantity } ], deliveryDetails: { name, phone, address, carrier } }`
**Optional header:** `Idempotency-Key: <UUID>` — one key per checkout attempt, generated by the client (D5). With the header a repeated submit returns the first order instead of placing a second one (see *Idempotency-Key* below); without it the steps run exactly as listed.

**Steps:**
1. Validate the request → `400`: *Request limits* for `items`; an `Idempotency-Key` that is not a UUID; every `deliveryDetails` field non-blank (`phone` in international format, e.g. `+380…`; `carrier` from the carriers the shop offers, e.g. `NOVA_POSHTA`). `customerId` comes from `X-User-Id`; a `customerId` in the body is ignored.
2. Generate the `orderId`.
3. Call Catalog batch-get for current prices **and** status. Any product missing or not `ACTIVE` → `422 { ineligibleItems }`; nothing is reserved. Client-provided prices are **never trusted** — the snapshot uses Catalog's `basePrice` and `title`. A total above `9,999,999,999.99` (the most Payment charges at once, and the size of `totalAmount`) → `422` type `order-total-too-large` with `maxTotal`; nothing is reserved.
4. Store a **reservation attempt** for the `orderId` (table `reservation_attempts`), then call Inventory `POST /inventory/reservations { orderId, items }`:
   - `204` → reserved.
   - `409` (the order already has active reservations) → treated as reserved: it means an earlier attempt with this `orderId` succeeded and its answer was lost.
   - `422 { insufficientItems }` → delete the attempt (nothing was reserved) and abort with `422 { insufficientItems }`; no order is created.
   - unavailable / timeout → `503`; no order is created. The reservation may exist or may still be made (a late answer), so release it at once (best-effort; release is idempotent) and **keep the attempt**.
5. In **one database transaction**: create the `Order` (`status = PENDING_PAYMENT`, `customerId`, `customerEmail`, `currency = UAH`, `reservationExpiresAt = now + ORDER_PAYMENT_WINDOW`, `totalAmount = Σ unitPriceAtOrder × quantity`, the item snapshots and `deliveryDetails`), write `OrderPlaced` to the outbox, and delete the attempt.
6. If step 5 fails after the reservation was made → release the reservation (best-effort) and answer `500`; the attempt stays. EP-ORD-JOB-03 releases every attempt older than its settle time that has no order, so a reservation is never left without an order, even if this instance dies between steps 4 and 5 or Inventory reserves after the release.
7. Return `201 Created { orderId, status, totalAmount, currency, reservationExpiresAt }` with `Location: /orders/{orderId}`.

**Idempotency-Key** (decisions D4–D10). Only when the header is present; the role check, step 1 (`400`) and the `X-User-Email` check (`401`) always run first, so a malformed request never claims a key.
- **Claim (after step 1, before step 2).** Compute the request hash (D9) and, in its own short transaction, insert `idempotency_keys (customer_id = X-User-Id, idempotency_key, request_hash, status = IN_PROGRESS, created_at = now)` with `INSERT … ON CONFLICT (customer_id, idempotency_key) DO NOTHING`. The key is scoped per customer: the same key from two customers is two independent requests.
- **The insert wrote a row** → this request owns the key; continue with step 2.
- **A row already exists** → nothing is called and nothing is reserved; answer from the stored row, checking the hash first:
  - `request_hash` differs → `422` type `idempotency-key-mismatch` (whatever the row's status).
  - `COMPLETED` → load the order `order_id` and return `201 Created` with the body of step 7 built from the order's **current** state (its `status` may already be `PAID` or later), `Location: /orders/{orderId}`, and the header `Idempotent-Replayed: true` (D7).
  - `IN_PROGRESS` → `409` type `request-in-progress` with `Retry-After: 1` ("A request with this Idempotency-Key is still being processed").
  - The row is gone when it is read (the first request failed and released it in between) → also `409 request-in-progress`; the retry claims the key itself.
  - A `COMPLETED` row whose order is missing cannot happen (the row is completed only in the transaction that stores the order) and would answer `500`.
  - `reservationExpiresAt` of a replay comes from the stored row and so has microsecond precision; the first answer may carry nanoseconds. Both are the same instant for any client.
- **Complete (inside the transaction of step 5).** Set the row to `COMPLETED` with `order_id` (only while it is still `IN_PROGRESS`), together with the order, `OrderPlaced` and the attempt deletion: either the order is stored and the key remembers it, or neither. If the row is gone (purged by EP-ORD-JOB-05 as stale, which cannot happen within a normal request), the order is still stored; log a warning.
- **Release on failure (D6).** Every answer other than `201` after the claim — `422` from step 3 or step 4, `503`, `500` from step 5/6 — deletes the row (only while it is still `IN_PROGRESS`, so a completed key is never released) in its own transaction (best-effort, logged on failure), so a retry with the same key runs from scratch with a new `orderId`. The reservation cleanup of steps 4–6 is unchanged: a reservation of the failed run is still released by its own attempt row and EP-ORD-JOB-03.
- **Purge.** EP-ORD-JOB-05 deletes stale `IN_PROGRESS` rows (left by an instance that died mid-request) and `COMPLETED` rows older than `ORDER_IDEMPOTENCY_KEY_TTL`; a key used after that is processed as new.

**Edge cases:**
- Invalid items or delivery details → `400`
- Any product not active or not found → `422 { ineligibleItems }`
- Insufficient stock → `422 { insufficientItems }`
- Catalog or Inventory unavailable → `503`; no order created, no stock left locked
- A price changes in Catalog right after the call → the order keeps the price it was placed with (snapshot)
- `Idempotency-Key` not a UUID → `400`
- Double submission (double click, retry after a lost answer, two tabs) **with** the same `Idempotency-Key` → one order and one reservation: a repeat after the first finished gets the same order (`201`, `Idempotent-Replayed: true`); a repeat while the first is still running gets `409 request-in-progress` and can retry after `Retry-After`
- Same key with another body (another cart or delivery details) → `422 idempotency-key-mismatch`; nothing created
- Retry with the same key after a `422`, `503` or `500` → processed as a new request (the failed run left no claim)
- Double submission **without** the header creates two orders, each with its own reservation; the unpaid one expires

**External calls:** Catalog `GET /catalog/products/batch-get`, Inventory `POST /inventory/reservations` (and release on failure) — **sync**
**Emits:** `OrderPlaced` → Notification (EP-NOTIF-EVT-01)
**Refs:** US-CUST-08, US-CUST-09, SCN-C14, SCN-C15, N-06

---

## EP-ORD-03: Pay for an Order

**Type:** `HTTP POST /orders/{orderId}/pay`
**Caller:** Customer (via API Gateway)
**Auth:** `CUSTOMER` (owner only)

**Request:** `{ cardDetails: { number, expiryMonth, expiryYear, cvv } }` — validated with the same rules as Payment's EP-PAY-01 (`400` before any call); card data is never stored or logged.

**Steps:**
1. Load the order; not found or not the caller's → `404`.
2. `status != PENDING_PAYMENT` → `409` ("Order is not awaiting payment"). `reservationExpiresAt <= now` → `409` (type `payment-window-expired`); no charge is attempted.
3. Call Payment `POST /payments/charge { orderId, amount: totalAmount, currency, cardDetails }` (read timeout `PAYMENT_READ_TIMEOUT`, at least 6 s; no retry by Order, Payment already repeats uncertain calls safely):
   - `200` → paid.
   - `409` "Order already charged" → paid (an earlier attempt succeeded and its answer was lost).
   - `402 { reason }` → `402 { reason }` to the customer; the order stays `PENDING_PAYMENT`, the payment window is **not** extended, and the customer may retry with another card.
   - `409` "Payment attempt in progress" → `409` to the customer ("Your previous payment is still being processed — try again with the same card").
   - `502` / `504` / timeout → `503`; the order is unchanged. The customer may retry with the **same** card; Payment then returns the real outcome without charging twice.
4. When paid: in one transaction, `PENDING_PAYMENT → PAID` (check-and-set), `paidAt = now`, and `OrderPaid` written to the outbox. Return `200 { orderId, status: "PAID" }`.
5. If the check-and-set fails because the order changed meanwhile (cancelled by the customer or expired), the money was taken for an order that is no longer payable: store the refund as owed (table `pending_refunds`), refund it at once (`POST /payments/refund`), log a warning, and answer `409`. The owed refund is deleted only when Payment confirms it (`200`, or `409` already refunded); otherwise EP-ORD-JOB-04 repeats it. The cancellation saga does not cover it, because the order was never `PAID`.

> **Expiry vs. payment.** A charge can take up to about 4.5 s. The expiry job (EP-ORD-JOB-01) expires an order only after `reservationExpiresAt` plus a grace period longer than `PAYMENT_READ_TIMEOUT`, so a payment started inside the window can always finish. Step 5 is the safety net for the remaining races.

**Edge cases:**
- Order not found or not the caller's → `404`
- Not `PENDING_PAYMENT` → `409`; window elapsed → `409`
- Card declined → `402`
- Payment unavailable or slow → `503` (retry with the same card is safe)

**External calls:** Payment `POST /payments/charge` (and `POST /payments/refund` in step 5) — **sync**
**Emits:** `OrderPaid` → Customer (EP-CUST-EVT-01), Delivery (EP-DEL-EVT-01), Notification (EP-NOTIF-EVT-02)
**Refs:** US-CUST-10, US-CUST-11, SCN-C16, SCN-C17

---

## EP-ORD-04: View Order History

**Type:** `HTTP GET /orders?page=&size=`
**Caller:** Customer (via API Gateway)
**Auth:** `CUSTOMER`

**Steps:**
1. Take `customerId` from `X-User-Id`.
2. Query the customer's orders, newest first (`placedAt DESC`), paginated (default size 20, at most 100).
3. Return `200 OK { content: [ { orderId, status, totalAmount, currency, placedAt } ], page, size, totalElements }`.

**Edge cases:**
- No orders → `200` with an empty `content`
- Invalid `page` / `size` → `400`

**External calls:** none
**Emits:** none
**Refs:** US-CUST-14, SCN-C20

---

## EP-ORD-05: View Order Details

**Type:** `HTTP GET /orders/{orderId}`
**Caller:** Customer (via API Gateway)
**Auth:** `CUSTOMER` (owner only)

**Steps:**
1. Load the order; not found or not the caller's → `404`.
2. If the order has reached `PAID` or later, fetch its shipment from Delivery: `GET /delivery/shipments?orderIds={orderId}`.
3. Return `200 OK { orderId, status, items: [ { productId, productTitle, unitPriceAtOrder, quantity } ], deliveryDetails, totalAmount, currency, placedAt, paidAt, reservationExpiresAt, ttn, deliveryStatus }`.

**Edge cases:**
- Not found or not the caller's → `404`
- No shipment yet (`OrderPaid` not processed) → `ttn` and `deliveryStatus` are `null`
- Delivery unavailable → degrade gracefully: return the order with `ttn` and `deliveryStatus` as `null`

**External calls:** Delivery `GET /delivery/shipments` — **sync**, optional
**Emits:** none
**Refs:** US-CUST-15, US-CUST-16, SCN-C21, SCN-C22

---

## EP-ORD-06: Cancel Order

**Type:** `HTTP DELETE /orders/{orderId}`
**Caller:** Customer (via API Gateway)
**Auth:** `CUSTOMER` (owner only)

**Steps:**
1. Load the order; not found or not the caller's → `404`.
2. `CANCELLATION_REQUESTED` → `409` ("Cancellation already in progress"). Any status outside `{ PENDING_PAYMENT, PAID, PENDING_SHIPPING }` → `409` ("Order cannot be cancelled in its current state").
3. Check-and-set the status to `CANCELLATION_REQUESTED` and record `statusBeforeCancellation` (decides whether a refund is owed).
4. Run the cancellation saga (orchestrated by Order, every step idempotent):
   1. Inventory `POST /inventory/reservations/{orderId}/release` (`204`, also when nothing is reserved).
   2. Delivery `DELETE /delivery/shipments/{orderId}` (`204`, also when no shipment exists yet: Delivery stores a tombstone so a late `OrderPaid` creates nothing). Skipped when cancelled from `PENDING_PAYMENT`.
   3. If `statusBeforeCancellation` is `PAID` or `PENDING_SHIPPING`: Payment `POST /payments/refund { orderId, amount: totalAmount, currency }` (`200`; `409` "already refunded" counts as done).
   4. In one transaction: `CANCELLATION_REQUESTED → CANCELLED` and `OrderCancelled { reason: "CUSTOMER_CANCELLED" }` to the outbox.
5. Return `202 Accepted { orderId, status }` — `CANCELLED` when the saga finished, `CANCELLATION_REQUESTED` when a step is still pending.

**When a step fails** (decision D3): a service is down or slow, or Payment answers `502` (refund rejected or provider unreachable). The order **stays `CANCELLATION_REQUESTED`**, the steps already done stay done, and the customer gets `202` with that status. EP-ORD-JOB-02 repeats the remaining steps later; because every step is idempotent, repeating a step that actually succeeded is harmless. Nothing is rolled back: released stock is not reserved again.

**Edge cases:**
- Not found or not the caller's → `404`
- Already in progress → `409`; `SHIPPED`, `DELIVERED`, `CANCELLED`, `EXPIRED` → `409`
- A `TTNGenerated` event arriving during the saga finds the order in `CANCELLATION_REQUESTED` and is ignored (see EP-ORD-EVT-01)
- A saga step fails → the order stays `CANCELLATION_REQUESTED`; EP-ORD-JOB-02 finishes it

**External calls:** Inventory release, Delivery cancel, Payment refund (if paid) — **sync**
**Emits:** `OrderCancelled` → Notification (EP-NOTIF-EVT-05)
**Refs:** US-CUST-13, SCN-C23, SCN-S07, N-09

---

## EP-ORD-07: View Fulfillment Queue (Inventory Worker)

**Type:** `HTTP GET /orders/fulfillment-queue`
**Caller:** Inventory Worker (via API Gateway)
**Auth:** `INVENTORY_WORKER`

**Steps:**
1. Query all orders with `status` in `{ PAID, PENDING_SHIPPING }`, oldest `paidAt` first.
2. Call Delivery once for all of them: `GET /delivery/shipments?orderIds=…` (at most 100 per call; larger queues are fetched in chunks of 100).
3. Merge per order: `{ orderId, status, placedAt, paidAt, items: [ { productId, productTitle, quantity } ], deliveryDetails, deliveryStatus, ttn, failureReason }`.
4. Return `200 OK { deliveryDataAvailable, orders: [ … ] }` (an envelope rather than a bare list, so the Delivery flag is part of the body; decided in task 14).

> Delivery status (`TTN_PENDING`, `TTN_FAILED`, `TTN_GENERATED`, `CANCELLED`, or `null` while `OrderPaid` is not processed yet) is **not** stored on the order; it lives only in Delivery Service.

**Edge cases:**
- Empty queue → `200 { deliveryDataAvailable: true, orders: [] }`
- Delivery unavailable → `deliveryDataAvailable: false` and the orders without delivery data (`deliveryStatus`, `ttn`, `failureReason` as `null`)

**External calls:** Delivery `GET /delivery/shipments` — **sync**, batch
**Emits:** none
**Refs:** US-INV-05, SCN-INV04

---

## EP-ORD-08: Mark Order as Shipped (Inventory Worker Dispatch)

**Type:** `HTTP POST /orders/{orderId}/ship`
**Caller:** Inventory Worker (via API Gateway)
**Auth:** `INVENTORY_WORKER`

**Steps:**
1. Load the order; not found → `404`.
2. `status` not in `{ PAID, PENDING_SHIPPING }` → `409`.
3. Fetch the shipment from Delivery (`GET /delivery/shipments?orderIds={orderId}`). Unless `status == TTN_GENERATED` → `409` ("TTN must be generated before dispatching").
4. In one transaction: check-and-set to `SHIPPED`, and `OrderShipped { orderId, ttn, customerEmail }` to the outbox.
5. Return `200 OK { orderId, status: "SHIPPED", ttn }`.

> An order still in `PAID` whose shipment already has a TTN (the `TTNGenerated` event has not been consumed yet) can be shipped: Delivery's answer is the source of truth for the TTN.

**Edge cases:**
- Not found → `404`
- Wrong status → `409`; TTN not generated (`TTN_PENDING`, `TTN_FAILED`, no shipment) → `409`
- Delivery unavailable → `503`

**External calls:** Delivery `GET /delivery/shipments` — **sync**
**Emits:** `OrderShipped` → Inventory (EP-INV-EVT-02), Delivery (EP-DEL-EVT-02), Notification (EP-NOTIF-EVT-03)
**Refs:** US-INV-07, SCN-INV06, N-09

---

## EP-ORD-EVT-01: Consume `TTNGenerated`

**Type:** Event consumer (consumer group `order-service`)
**Producer:** Delivery Service, topic `delivery.shipment-events`, header `eventType: TTNGenerated`
**Payload received:** `{ eventId, orderId, ttn, timestamp }`

**Steps:**
1. Load the order. Not found → log a warning and acknowledge.
2. `status == PAID` → check-and-set to `PENDING_SHIPPING`. Any other status → log and acknowledge (already `PENDING_SHIPPING` = duplicate; `SHIPPED` = the worker was faster; `CANCELLATION_REQUESTED` / `CANCELLED` = cancelled meanwhile, Delivery cancels the TTN itself).
3. Acknowledge.

**Idempotency:** by state; no processed-events table needed.
**Failures:** one listener for the Delivery topic dispatches on `eventType`; an unreadable payload goes to the dead-letter topic `order.delivery.shipment-events.dlt` (owned by Order) at once, other failures after 4 retries with back-off.
**Refs:** order-state-machine.md (PAID → PENDING_SHIPPING), SCN-S04

---

## EP-ORD-EVT-02: Consume `OrderDelivered`

**Type:** Event consumer (same listener as EP-ORD-EVT-01)
**Producer:** Delivery Service, header `eventType: OrderDelivered`
**Payload received:** `{ eventId, orderId, ttn, timestamp }`

**Steps:**
1. Load the order. Not found → log a warning and acknowledge.
2. `status == SHIPPED` → check-and-set to `DELIVERED`. `DELIVERED` → duplicate, acknowledge. Any other status → log a warning and acknowledge.
3. Acknowledge.

**Idempotency:** by state.
**Refs:** order-state-machine.md (SHIPPED → DELIVERED), SCN-S08

---

## EP-ORD-JOB-01: Payment Window Expiry

**Type:** Scheduled job (every minute)

**Steps:**
1. Find orders with `status = PENDING_PAYMENT` and `reservationExpiresAt + grace <= now`, where `grace` is longer than `PAYMENT_READ_TIMEOUT` (default 30 s; see EP-ORD-03).
2. For each, oldest first:
   1. Inventory `POST /inventory/reservations/{orderId}/release`. On failure → log, leave the order `PENDING_PAYMENT`, try again in the next run.
   2. In one transaction: check-and-set `PENDING_PAYMENT → EXPIRED` and `OrderCancelled { reason: "EXPIRED" }` to the outbox. If the check-and-set fails (the order was paid or cancelled meanwhile), skip it.
3. Continue with the next order; one failure never stops the run.

> `EXPIRED` is a system-initiated terminal state; it does not go through `CANCELLATION_REQUESTED`. No payment was made, so no refund is owed. With several replicas, rows are claimed with `FOR UPDATE SKIP LOCKED` (or the job runs on one replica) so an order is not processed twice at once; a second processing would be harmless anyway (release is idempotent, the check-and-set fails).

**External calls:** Inventory release — **sync**, per order
**Emits:** `OrderCancelled` → Notification (EP-NOTIF-EVT-05)
**Refs:** SCN-S06, N-06, order-state-machine.md

---

## EP-ORD-JOB-02: Finish Interrupted Cancellations

**Type:** Scheduled job (every minute)

**Steps:**
1. Find orders in `CANCELLATION_REQUESTED` whose last saga attempt is older than the retry delay (back-off 1 min, 5 min, 15 min, then every 30 min).
2. For each, repeat the saga of EP-ORD-06 step 4 from the beginning (every step is idempotent: release `204`, Delivery cancel `204`, refund `409` = already refunded).
3. All steps succeed → `CANCELLED` and `OrderCancelled { reason: "CUSTOMER_CANCELLED" }` in one transaction.
4. A step fails → count the attempt, store the failed step and its error (`cancellationFailure`), log a warning. After **5** failed attempts the order is flagged `needsManualAttention = true` and an `ERROR` is logged (the alert); the job keeps retrying every 30 min, because a refund owed to a customer must not be dropped.

> A rejected refund usually means the provider will keep rejecting it (Payment's refund key is fixed per order and a provider replays a stored rejection). The flag is how such orders reach a person; resolving them (for example a manual refund at the provider) is outside the application.

**External calls:** Inventory release, Delivery cancel, Payment refund — **sync**, per order
**Emits:** `OrderCancelled` → Notification
**Refs:** SCN-S07, D3

---

## EP-ORD-JOB-03: Release Orphan Reservations

**Type:** Scheduled job (every `ORDER_RECOVERY_INTERVAL`, default 1 minute)

**Steps:**
1. Find reservation attempts (EP-ORD-02 step 4) older than the settle time (2 minutes, far longer than a reservation call with its retries), oldest first.
2. If an order with that ID exists, the attempt is only stale: delete it.
3. Otherwise release the reservation (Inventory `POST /inventory/reservations/{orderId}/release`) and delete the attempt. On failure keep it, count the failure and try again in the next run; after 5 failures log an `ERROR`.

> Without this job a reservation whose order was never stored (the save failed, the instance died, or Inventory reserved after Order had given up) would hold stock forever: Inventory reservations do not expire, and EP-ORD-JOB-01 only knows stored orders. Several replicas need no claim: release is idempotent.

**External calls:** Inventory release — **sync**, per attempt
**Emits:** none

---

## EP-ORD-JOB-04: Repeat Owed Refunds

**Type:** Scheduled job (every `ORDER_RECOVERY_INTERVAL`, default 1 minute)

**Steps:**
1. Find owed refunds (EP-ORD-03 step 5) whose back-off has passed (the cancellation's: 1, 5, 15, then every 30 minutes).
2. Payment `POST /payments/refund { orderId, amount, currency }`: `200` or `409` already refunded → delete the owed refund; anything else → count the attempt and store the error.
3. After 5 failed attempts the refund is flagged `needsManualAttention` with one `ERROR` log line; retries continue.

> Several replicas need no claim: Payment refunds an order at most once (fixed refund key) and answers a repeat with `409`.

**External calls:** Payment refund — **sync**, per refund
**Emits:** none

---

## EP-ORD-JOB-05: Purge Idempotency Keys

**Type:** Scheduled job (every `ORDER_RECOVERY_INTERVAL`, default 1 minute)

**Steps:**
1. Delete `idempotency_keys` rows with `status = IN_PROGRESS` and `created_at` older than the settle time of EP-ORD-JOB-03 (2 minutes, far longer than a submit request with its Catalog and Inventory calls and their retries). Such a row belongs to a request whose instance died; deleting it lets the customer's retry with the same key run (D10). The reservation of that request, if any, is released by EP-ORD-JOB-03 through its attempt row.
2. Delete rows with `status = COMPLETED` and `created_at` older than `ORDER_IDEMPOTENCY_KEY_TTL` (default `24h`). The order stays; only the key is forgotten.
3. Each rule is one bulk `DELETE` (`created_at` strictly before the cutoff, served by the `(status, created_at)` index) in its own transaction: the number of deleted rows is logged at `DEBUG`, a failure at `WARN`, a failure of rule 1 does not stop rule 2, and the next run tries again. No batch limit: the rows are small.

> Several replicas need no claim: deleting the same rows twice is harmless. A request is never still running when its `IN_PROGRESS` row is purged, for the same reason the settle time protects EP-ORD-JOB-03.

**External calls:** none
**Emits:** none
**Refs:** D10

---

## Decisions

Agreed on 2026-10-16:

| # | Question | Decision | Consequence |
|---|---|---|---|
| D1 | How does Order learn the customer's email? The JWT carried only `sub` and `role`; the email lives in Auth. | Auth adds an `email` claim to the JWT; the gateway forwards it as `X-User-Email` (and strips client copies). | Changes in Auth, the gateway and the Security Model. Rejected: a contact email in the request (can differ from the account), a lookup by Notification (couples Notification to Auth). |
| D2 | Where does the soft check get stock availability? | Catalog's batch-get also returns `stockStatus` (`IN_STOCK` / `OUT_OF_STOCK`), which Catalog already stores. | One Catalog change. It cannot catch "2 left, 5 requested"; the reservation does. Rejected: a new Inventory batch endpoint. |
| D3 | What happens when a cancellation step fails? | The order stays `CANCELLATION_REQUESTED`, the customer gets `202`, EP-ORD-JOB-02 repeats the idempotent steps with back-off and flags the order for manual handling after 5 failures. | Two technical fields on `Order` (`cancellationAttempts`, `cancellationFailure`, plus `needsManualAttention`). Rejected: answering `503` and rolling back (released stock would have to be re-reserved). |

Agreed on 2026-10-22 (idempotent order submission, frontend backlog [BL-07](../../../frontend/docs/backlog/BL-07-order-submit-idempotency.md)):

| # | Question | Decision | Consequence |
|---|---|---|---|
| D4 | How does EP-ORD-02 recognise a repeated submit, including two that run at the same time? | A technical table `idempotency_keys (customer_id, idempotency_key, request_hash, status IN_PROGRESS \| COMPLETED, order_id, created_at)`, unique on `(customer_id, idempotency_key)`. A request claims its key with `INSERT … ON CONFLICT DO NOTHING` before calling Catalog; the row becomes `COMPLETED` in the transaction that stores the order. | A concurrent duplicate sees the claim and never reaches Inventory. Rejected: a unique key column on `orders` (two concurrent duplicates would both reserve, and the loser could answer `422 insufficient-stock` for an order that exists); an `orderId` derived from the key (Inventory's `409` would absorb repeats, but the loser's failure path would release the winner's reservation). |
| D5 | Is the header required, and what may it contain? | Optional; a UUID (`400` otherwise); scoped per customer. | Clients without the header (tests, API collections) behave as before. A fixed-size column and trivial validation. |
| D6 | Which outcomes are remembered? | Only success. Any other answer after the claim deletes the row. | A retry after `503` or `500` actually retries; after a `422` the cart changes anyway. Rejected: replaying stored errors (a replayed `503` would make the retry useless). |
| D7 | What does a replay return? | `201` built from the order's current state, same `Location`, header `Idempotent-Replayed: true`. | No response body is stored; the order is the result. `status` may differ from the first answer (e.g. `PAID`). |
| D8 | How are conflicts answered? | Same key, different body → `422` type `idempotency-key-mismatch` (checked before the status). Same key while the first request runs → `409` type `request-in-progress` with `Retry-After: 1`. | As in the IETF *Idempotency-Key* HTTP header draft. Both use the plain `Problem` schema; clients tell them apart by `type`. |
| D9 | When is a body "the same"? | SHA-256 (hex) of the parsed request serialised as JSON with `items` sorted by `productId`. | Whitespace, JSON field order and item order do not count as a different body; any changed quantity, product or delivery field does. |
| D10 | How are keys cleaned up, including a claim left by an instance that died? | EP-ORD-JOB-05 deletes `IN_PROGRESS` rows older than the 2-minute settle time and `COMPLETED` rows older than `ORDER_IDEMPOTENCY_KEY_TTL` (default 24 h). | A stranded claim blocks the key for at most about 3 minutes. 24 h is far longer than the 15-minute payment window, so any realistic retry is covered. Rejected: taking over a stale claim inside the claim statement (harder to read and test). |

---

## Changes from the First Spec Version

| Area | First version | Now | Why |
|---|---|---|---|
| Who can order | guests and registered customers | registered customers only (`CUSTOMER`) | decision 2026-10-16 |
| Guest data | `guestEmail`, `orderAccessToken`, `?token=` access, emailed cancellation verification for guests | removed; `customerId` is never null | follows from the above |
| Access to someone else's order | `403` | `404` (same as not found) | do not reveal which order IDs exist |
| `POST /orders/validate` | `200 OK` | `204 No Content` | nothing to return; same convention as Inventory |
| Catalog batch-get fields | assumed `price`, Inventory batch availability check (TBD) | Catalog's contract `{ productId, title, basePrice, status }` plus `stockStatus` (D2) | aligned with the implemented Catalog; no new Inventory endpoint |
| Item lists | non-empty | 1–100 entries, no duplicate `productId` | same limits as Catalog batch-get and Inventory reservation |
| Inventory reservation answer | `{ reservationId, expiresAt }` | `204`, `409` = already reserved; Order owns the payment window | aligned with the implemented Inventory |
| Order snapshot | `productName` TBD | `productTitle` from Catalog; `customerEmail` (from `X-User-Email`, D1), `paidAt`, `statusBeforeCancellation`, `version`, cancellation-retry fields added | needed by events, history and the saga |
| Submit response | `{ orderId, orderAccessToken }` | `{ orderId, status, totalAmount, currency, reservationExpiresAt }` | no guest token |
| Payment answers | `402`, timeout → `503` | also `409` already charged = paid, `409` attempt in progress, `502`/`504` → `503` with "retry with the same card"; read timeout ≥ 6 s; refund if paid after the order changed | aligned with Payment's idempotency keys and repeats |
| Expiry vs. payment race | conditional update only | plus a grace period longer than the payment timeout, and a refund safety net | a charge can take seconds |
| Saga | Delivery cancel only "if TTN", failure handling TBD | Delivery cancel always after payment (Delivery stores a tombstone), refund `409` counts as done; a failed step leaves the order `CANCELLATION_REQUESTED` and EP-ORD-JOB-02 finishes it (D3) | aligned with Delivery and Payment |
| Events | payloads without IDs/time, `reason: CUSTOMER_REQUESTED` in the catalogue | topic, key, headers, `eventId` + `timestamp` on every event, `customerId` on all, `reason: CUSTOMER_CANCELLED`, outbox | same conventions as Catalog, Inventory and Delivery events |
| Fulfillment queue | statuses `TTN_PENDING/FAILED/GENERATED` | also `CANCELLED`; chunks of 100; `deliveryDataAvailable` flag | aligned with Delivery's batch limit |

### Other documents updated with this revision (2026-10-16)

- [entities.md](../entities/entities.md) — `Order` without `guestEmail` / `orderAccessToken`; `customerId` never null; `customerEmail`, `paidAt`, `statusBeforeCancellation`, cancellation-retry fields, `version`, `productTitle`.
- [events-catalogue.md](../services-requirements/events-catalogue.md) — section *Order Lifecycle* with topic/key/header conventions, `eventId` and `timestamp` on every event, `customerId` always set, `reason: CUSTOMER_CANCELLED`.
- [api-contracts.md](../services-requirements/api-contracts.md) — Order rows (`CUSTOMER` only, new request/response shapes); Catalog batch-get with `stockStatus`.
- [API Gateway spec](api-gateway-spec.md) — every `/orders` customer route `Required` + `CUSTOMER`; `X-User-Email` stripped and set (D1).
- [Security Model](../services-requirements/security-model.md) and [Auth spec](auth-service-spec.md) — `email` JWT claim and the `X-User-Email` header (D1).
- [Catalog spec](catalog-service-spec.md) — EP-CAT-09 returns `stockStatus` (D2).
- [Customer spec](customer-service-spec.md) — EP-CUST-EVT-01 step 2 kept only as a defensive guard; [Notification spec](notification-service-spec.md) — `customerId` of `OrderPaid` no longer nullable.
- Customer scenarios (SCN-C11, C14, C21 updated; SCN-C18, C19, C24 marked out of scope), user stories (US-CUST-07 out of scope), technical insights §4, review notes (N-11 obsolete, new N-13).

The code changes these documents imply in Auth, the gateway and Catalog are tasks of their own (see [`docs/tasks/order-service/`](../../tasks/order-service/)).

### Revision 2026-10-22: idempotent order submission

`POST /orders` accepts an optional `Idempotency-Key` header (EP-ORD-02, decisions D4–D10), replacing the edge case "double submission creates two orders … accepted for now". *Synced with the implementation of tasks 19–20 on 2026-10-22:* the answer when the row disappears between the claim and its lookup, complete and release acting only on `IN_PROGRESS` rows, the precision of a replayed `reservationExpiresAt`, and the transactions and log levels of EP-ORD-JOB-05. New: the technical table `idempotency_keys`, the problem types `idempotency-key-mismatch` (`422`) and `request-in-progress` (`409`), the response header `Idempotent-Replayed`, EP-ORD-JOB-05 and `ORDER_IDEMPOTENCY_KEY_TTL`. Implementation: [task 19](../../tasks/order-service/19-idempotent-order-submission.md), then [task 20](../../tasks/order-service/20-purge-idempotency-keys-job.md).

Other documents updated with this revision (2026-10-22):
- [api-contracts.md](../services-requirements/api-contracts.md) — the `POST /orders` row: optional header, replay, `409` and `422` answers (and the `422 order-total-too-large` answer, which was missing there; the type list above lacked it too).

No gateway change: it forwards every request header except `Authorization`, the `X-User-*` headers and `X-Internal-Token`.
