# Customer Scenarios

> Draft interaction scenarios for the Customer actor.
> These describe system behaviour from the customer's perspective and serve as input for deriving the order state machine, event catalogue, and service interaction diagrams.

---

## SCN-C01: Customer Registration — Happy Path

1. Customer sends `POST /auth/register` with `{email, password}`.
2. Auth Service validates: email format correct, email not already registered, password meets strength rules.
3. Auth Service creates `User(id=UUID, email, hashedPassword, role=CUSTOMER)`.
4. Auth Service returns `201 Created` with `{userId}`.

*No event is published. Customer Service is not involved. Customer profile is created lazily on first use.*

---

## SCN-C02: Customer Registration — Validation Failure

1. Customer sends `POST /auth/register` with `{email, password}`.
2. Auth Service validates → finds invalid email format, duplicate email, or weak password.
3. Auth Service returns `400 Bad Request` with a specific error message.

---

## SCN-C03: Customer Login — Happy Path

1. Customer sends `POST /auth/login` with `{email, password}`.
2. Auth Service looks up user by email, verifies hashed password.
3. Auth Service issues and returns `JWT(customerId, role=CUSTOMER, exp)`.
4. Client stores JWT and attaches it to subsequent requests.

---

## SCN-C04: Customer Login — Invalid Credentials

1. Customer sends `POST /auth/login` with `{email, password}`.
2. Auth Service finds no matching user or password mismatch.
3. Auth Service returns `401 Unauthorized`.

---

## SCN-C05: Browse & Filter Products

1. Customer sends `GET /catalog/products` with optional query params: `search`, `categoryId`, `minPrice`, `maxPrice`, `sortBy`, `page`.
2. Catalog Service queries its own database for products with `status=ACTIVE` matching the filters.
3. Catalog Service returns a paginated list of products, each including coarse stock status (`IN_STOCK` or `OUT_OF_STOCK`) from its own read-model.

*Stock status in the read-model is updated asynchronously by Inventory events — no sync call to Inventory Service happens here.*

---

## SCN-C06: View Product Details

1. Customer sends `GET /catalog/products/{productId}`.
2. Catalog Service returns the full product record: title, description, category, base price, coarse stock status, and delivery options info.

*Only ACTIVE products are visible. NOT_ACTIVE products return 404 to unauthenticated/customer requests.*

---

## SCN-C07: Add Product to Cart (Registered)

1. Customer sends `POST /customers/cart/items` with `{productId, quantity}` and a valid JWT.
2. Customer Service extracts `customerId` from JWT.
3. Customer Service upserts the cart item `(productId, quantity)` for this customer. If the item already exists, quantity is updated (or incremented — TBD).
4. Returns the updated cart item list.

*Cart stores only `(productId, quantity)` pairs — no product name or price snapshot.*

---

## SCN-C08: View Cart

1. Customer sends `GET /customers/cart` with a valid JWT.
2. Customer Service returns the list of `(productId, quantity)` for this customer.
3. Client fetches current product data from Catalog Service for each `productId`.
4. Client renders the cart:
   - Available items: shown with current price, controls enabled, included in subtotal.
   - `NOT_ACTIVE` or `OUT_OF_STOCK` items: shown with "Unavailable" badge, quantity controls disabled, excluded from subtotal, "Remove" action available.

*Items are never silently deleted from the cart when they become unavailable.*

---

## SCN-C09: Modify Cart — Change Quantity or Remove Item

1. Customer sends `PATCH /customers/cart/items/{productId}` with `{quantity}` to update quantity, or `DELETE /customers/cart/items/{productId}` to remove.
2. Customer Service updates or removes the record for this customer.
3. Returns updated cart.

---

## SCN-C10: Guest Cart → Login → Merge

1. Guest browses and accumulates cart items in `localStorage`.
2. Guest logs in and receives a JWT.
3. Client reads `localStorage` cart, sends `POST /customers/cart/merge` with `{items: [{productId, quantity}]}` and the new JWT.
4. Customer Service merges guest items into the backend cart for `customerId`.
5. Client clears `localStorage` cart.

*Merge conflict resolution (duplicate productId in both carts) is a client-side concern — deferred.*

---

## SCN-C11: Checkout Soft Check — Happy Path

Triggered when customer clicks "Proceed to Checkout".

1. Client sends `POST /orders/validate` with `{items: [{productId, quantity}]}` (JWT or no JWT for guest).
2. Order Service calls Catalog Service: are all `productIds` currently `ACTIVE`?
3. Order Service calls Inventory Service: is `availableQty >= requestedQty` for all items?
4. Both checks pass → Order Service returns `200 OK`.
5. Client navigates to the checkout form.

---

## SCN-C12: Checkout Soft Check — Failure

1. Same as SCN-C11, but one or more items are `NOT_ACTIVE` or have insufficient stock.
2. Order Service returns `422 Unprocessable Entity` with `{ineligibleItems: [{productId, reason}]}`.
3. Client highlights the problematic items in the cart and blocks navigation to the checkout form.

---

## SCN-C13: Prefill Delivery Details (Registered Customer)

1. Customer arrives at checkout form.
2. Client sends `GET /customers/me/profile` with JWT.
3. Customer Service returns `CustomerInfo` record if it exists (name, phone, saved address).
4. If no profile exists yet (lazy creation — user never saved one), returns empty/null fields.
5. Client pre-populates the form with the returned data.

---

## SCN-C14: Order Submission & Hard Stock Reservation — Happy Path

Customer submits the completed checkout form ("Submit & Pay").

1. Client sends `POST /orders` with `{items: [{productId, quantity}], deliveryDetails: {name, phone, address, carrier}}` and JWT (or no JWT for guest with `contactEmail`).
2. Order Service calls Catalog Service: fetch current prices for all `productIds` (for snapshot — client-provided prices are never trusted).
3. Order Service calls Inventory Service: `POST /stock/reserve` with `{items}`.
   - Inventory Service atomically checks `availableQty >= requestedQty` for all items.
   - Atomically decrements `availableQty`, increments `reservedQty` for all items.
   - Returns `{reservationId, expiresAt}`.
4. Order Service creates the Order:
   - `status = PENDING_PAYMENT`
   - `OrderItemSnapshots`: `[{productId, productName, unitPriceAtOrder, quantity}]`
   - `deliveryDetails`, `customerId` (or `guestEmail`)
   - `reservationId`, reservation `expiresAt`
5. Order Service publishes event: `OrderPlaced(orderId)`.
6. Notification Service consumes `OrderPlaced` → sends "Order Placed" email.
7. Order Service returns `201 Created` with `{orderId, paymentUrl}`.

---

## SCN-C15: Order Submission Failure — Item Became Unavailable During Form Fill

1. Same as SCN-C14 step 3, but between the soft check and submission, an item ran out of stock or was deactivated.
2. Inventory Service rejects the reservation (insufficient quantity), or Catalog Service returns `NOT_ACTIVE`.
3. Order Service returns `422` with details of affected items. No order is created, no stock is locked.
4. Customer is redirected back to their cart to adjust the selection.

---

## SCN-C16: Card Payment — Happy Path

1. Customer submits card details to Order Service: `POST /orders/{orderId}/pay {cardDetails}`.
2. Order Service validates: `Order.status == PENDING_PAYMENT` and `reservationExpiresAt > now()`.
   - If the order is expired or in any other status → returns `409 Conflict`. No charge is attempted.
3. Order Service calls Payment Service to charge the order amount.
4. Payment Service calls the payment gateway.
5. Gateway returns success.
6. Payment Service records `PaymentRecord(orderId, amount, status=SUCCESS, transactionId)` and returns success to Order Service.
7. Order Service sets `Order.status = PAID`.
8. Order Service publishes event: `OrderPaid(orderId)`.
9. Customer Service consumes `OrderPaid` → clears backend cart for `customerId`.
10. Delivery Service consumes `OrderPaid` → begins automatic TTN generation (see SCN-S04).
11. Notification Service consumes `OrderPaid` → sends "Payment Received" email.

---

## SCN-C17: Card Payment — Gateway Failure & Retry

1. Customer submits card details to Order Service: `POST /orders/{orderId}/pay {cardDetails}`.
2. Order Service validates order state (same as SCN-C16 step 2) — passes.
3. Order Service calls Payment Service to charge the order amount.
4. Payment Service calls the payment gateway.
5. Gateway returns failure (e.g., insufficient funds, card declined).
6. Payment Service records `PaymentRecord(orderId, status=FAILED)` and returns failure to Order Service.
7. Order Service returns payment failure to the customer. Order remains `PENDING_PAYMENT`.
8. Customer may retry with a different card — restarts from step 1.

*Each retry does not reset the reservation TTL. If the TTL expires between retries, the next attempt is rejected at step 2 with `409 Conflict` (SCN-S06).*

---

## SCN-C18: Guest Checkout — Place Order Without Account

1. Guest completes soft check (SCN-C11) without a JWT.
2. Guest fills checkout form including `contactEmail`.
3. Guest sends `POST /orders` with `{items, deliveryDetails, contactEmail}` (no JWT).
4. Order Service processes identically to SCN-C14, but stores `guestEmail` instead of `customerId`.
5. Order is created with a `orderAccessToken` (secure random string) in addition to `orderId`.
6. Order Service/Notification Service sends "Order Placed" email to `contactEmail` with a secret URL: `/orders/{orderId}?token={orderAccessToken}`.

---

## SCN-C19: Guest Order Access via Secret URL

1. Guest navigates to `/orders/{orderId}?token={orderAccessToken}` (from email link).
2. Order Service validates the `orderAccessToken` against the stored value for `orderId`.
3. If valid → returns full order details (same as SCN-C22).
4. If invalid or missing → returns `403 Forbidden`.

---

## SCN-C20: View Order History (Registered)

1. Customer sends `GET /orders` with JWT.
2. Order Service returns a paginated list of orders for `customerId`: `[{orderId, status, placedAt, totalAmount}]`.

---

## SCN-C21: View Order Details with Price Snapshot

1. Customer sends `GET /orders/{orderId}` with JWT, or guest accesses with `?token=...`.
2. Order Service returns full order: `OrderItemSnapshots` (productName, unitPriceAtOrder, quantity), delivery details, status, TTN if available.

*Prices in the snapshot are always the prices at the time of ordering, regardless of any subsequent Catalog price changes.*

---

## SCN-C22: Delivery Tracking

1. Customer views order details (SCN-C21).
2. Response includes `ttn` (tracking code) once generated, and current delivery status.
3. Customer uses the TTN to track the parcel externally via the carrier's website, or the system shows the carrier status up to `DELIVERED`.

---

## SCN-C23: Order Cancellation — Registered Customer (Before SHIPPED)

1. Customer sends `DELETE /orders/{orderId}` with JWT.
2. Order Service verifies: order belongs to `customerId` AND `status` is in `{PENDING_PAYMENT, PAID, PENDING_SHIPPING}`.
3. If valid → Order Service sets `status = CANCELLATION_REQUESTED`.
4. Cancellation Saga executes (see SCN-S07).

---

## SCN-C24: Order Cancellation — Guest Customer (Before SHIPPED)

1. Guest sends `DELETE /orders/{orderId}?token={orderAccessToken}`.
2. Order Service verifies: `orderAccessToken` is valid AND `status` allows cancellation.
3. Order Service dispatches a one-time cancellation verification link or code to `guestEmail`.
4. Guest clicks the link / enters the code.
5. Upon confirmation → Order Service sets `status = CANCELLATION_REQUESTED`.
6. Cancellation Saga executes (see SCN-S07).

*Token expiry and resend policy details are deferred.*

---

## SCN-C25: Save / Update Customer Profile

1. Customer sends `PUT /customers/me/profile` with `{firstName, lastName, phone, defaultAddress}` and JWT.
2. Customer Service upserts `CustomerInfo` for `customerId` (creates if it doesn't exist — lazy creation).
3. Returns updated profile.
