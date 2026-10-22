# REST API Contracts

> This document defines the synchronous HTTP API contracts for all microservices. 
> These are the endpoints that will be exposed to clients (via the API Gateway) or used for internal service-to-service communication.

## Shared Conventions

- **Errors:** RFC 9457 problem details (`application/problem+json`, `type` = `https://voltstore.com/errors/<kind>`), with service-specific extensions listed per endpoint below.
- **Paging envelope:** every paged list answers `{ content: [...], page, size, totalElements }`. `page` is zero-based, and `size` is the effective page size (the requested one, or the default). The query parameters are `page` and `size`; each endpoint states its default and maximum `size`.
- **OpenAPI:** Auth, Catalog, Customer, Inventory, Order and Delivery publish OpenAPI 3.1 documents generated from the code, committed as `<service>/openapi/public.json` (and `internal.json`). How they are produced, and the named problem schemas, are in [OpenAPI Conventions](openapi-conventions.md). This document stays the design; the OpenAPI files describe the implementation.

---

## 1. Auth Service

| Method | Path | Auth Role | Description |
|--------|------|-----------|-------------|
| `POST` | `/user/register` | None | Register a new customer. <br> **Req:** `{email, password}` <br> **Res:** `201 Created {userId}` or `400` (field errors) or `409 Conflict` (email in use) |
| `POST` | `/auth/login` | None | Authenticate and get JWT. <br> **Req:** `{email, password}` <br> **Res:** `200 OK {token}` or `400` or `401` (invalid credentials) |
| `POST` | `/user/non-customer` | `ADMIN` | Create a staff or admin account. <br> **Req:** `{email, password, role}` (`role` is any role except `CUSTOMER`) <br> **Res:** `201 Created {userId}` or `400` or `401` or `403` or `409 Conflict` |

---

## 2. Customer Service

| Method | Path | Auth Role | Description |
|--------|------|-----------|-------------|
| `GET` | `/customers/me/profile` | `CUSTOMER` | Get profile; a customer who never saved one gets all fields `null` (no row is created). <br> **Res:** `200 OK {firstName, lastName, phone, defaultAddress}` |
| `PUT` | `/customers/me/profile` | `CUSTOMER` | Upsert profile. <br> **Req:** `{firstName, lastName, phone, defaultAddress}` (names required; `phone` required, E.164; `defaultAddress` optional) <br> **Res:** `200 OK {firstName, lastName, phone, defaultAddress}` or `400` (field errors) |
| `GET` | `/customers/cart` | `CUSTOMER` | Get cart items (missing cart → empty). <br> **Res:** `200 OK [{productId, quantity}]` |
| `PUT` | `/customers/cart` | `CUSTOMER` | Replace the whole cart (idempotent). <br> **Req:** `{items: [{productId, quantity}]}` <br> **Res:** `200 OK [{productId, quantity}]` or `400` or `422` (duplicate `productId`, > 10 products, quantity > 10) |
| `POST` | `/customers/cart/merge` | `CUSTOMER` | Merge items into the cart, summing quantities of products already in it (not idempotent). <br> **Req:** `{items: [{productId, quantity}]}` <br> **Res:** `200 OK [{productId, quantity}]` or `400` or `422` (duplicate `productId`, merged result > 10 products or any quantity > 10) |
| `DELETE`| `/customers/cart` | `CUSTOMER` | Clear the cart (deletes all items, keeps the cart; idempotent). <br> **Res:** `200 OK []` |
| `POST` | `/customers/cart/items` | `CUSTOMER` | Add item or increment quantity. <br> **Req:** `{productId, quantity}` <br> **Res:** `200 OK [{productId, quantity}]` or `400` or `422` (cart would exceed 10 products or quantity 10) |
| `PATCH`| `/customers/cart/items/{productId}`| `CUSTOMER` | Set explicit quantity. <br> **Req:** `{quantity}` <br> **Res:** `200 OK [{productId, quantity}]` or `400` or `404` (item not in cart) or `422` (quantity > 10) |
| `DELETE`|`/customers/cart/items/{productId}`| `CUSTOMER` | Remove item from cart (idempotent). <br> **Res:** `200 OK [{productId, quantity}]` |

Cart rules: at most 10 distinct products per cart, quantity 1–10 per product. `400` = malformed request (including `quantity` < 1); `422` = a cart rule is broken (Problem Details body with a `violation` code). See the Customer Service spec, *Cart Aggregate* section.

---

## 3. Catalog Service

| Method | Path | Auth Role | Description |
|--------|------|-----------|-------------|
| `GET` | `/catalog/products` | None/`CATALOG_MANAGER`| Paginated search. Customers see only ACTIVE. Managers can filter by `status`. <br> **Query:** `search, categoryId, minPrice, maxPrice, sortBy (title\|basePrice[,asc\|desc]), status, page (zero-based), size (default 20, max 100)` <br> **Res:** `200 OK {content: [{productId, title, basePrice, categoryId, status, stockStatus}], page, size, totalElements}` |
| `GET` | `/catalog/products/{id}` | None | Get product details (ACTIVE only; inactive → same `404` as missing). <br> **Res:** `200 OK {productId, title, description, categoryId, basePrice, stockStatus}` |
| `POST` | `/catalog/products` | `CATALOG_MANAGER`| Create product in NOT_ACTIVE state. <br> **Req:** `{title, description, categoryId, basePrice}` <br> **Res:** `201 Created {productId}` |
| `PUT` | `/catalog/products/{id}` | `CATALOG_MANAGER`| Update product details. <br> **Req:** `{title, desc, categoryId, basePrice}` <br> **Res:** `200 OK` |
| `PATCH`| `/catalog/products/{id}/status`| `CATALOG_MANAGER`| Activate/Deactivate product. <br> **Req:** `{status}` <br> **Res:** `200 OK` or `422 Unprocessable` (if activation fails readiness check) |
| `GET` | `/catalog/categories` | None | List/search categories (EP-CAT-10, added to the original spec). <br> **Query:** `search, page (zero-based), size (default 20, max 100)` <br> **Res:** `200 OK {content: [{categoryId, name}], page, size, totalElements}` |
| `POST` | `/catalog/categories` | `CATALOG_MANAGER`| Create category. <br> **Req:** `{name}` <br> **Res:** `201 Created {categoryId}` |
| `PUT` | `/catalog/categories/{id}`| `CATALOG_MANAGER`| Rename category. <br> **Req:** `{name}` <br> **Res:** `200 OK` |
| `DELETE`|`/catalog/categories/{id}`| `CATALOG_MANAGER`| Delete category. <br> **Res:** `200 OK` or `409 Conflict` (if products attached) |
| `GET` | `/catalog/products/batch-get`| Internal | Fetch current product data for Order Service snapshotting and for Inventory's existence check. <br> **Query:** `?ids=uuid1,uuid2` (1–100 IDs) <br> **Res:** `200 OK [{productId, title, basePrice, status, stockStatus}]` — a flat array of the products that exist, in any status; unknown IDs are simply absent (the spec's `notFound` list was dropped on purpose). `400` for empty, malformed or too many `ids`. |

---

## 4. Inventory Service

> Errors use the shared `ProblemDetail` shape. A success with nothing to return is `204 No Content`. Internal routes require `X-Internal-Token` (role `SERVICE`) and are not routed by the API Gateway. Full rules: [Inventory Service Spec](../services-specs/inventory-service-spec.md).

| Method | Path | Auth Role | Description |
|--------|------|-----------|-------------|
| `GET` | `/inventory/stock/{id}/readiness`| Internal | (Used by Catalog sync) Check that a stock item exists and its packaging is configured. <br> **Res:** `204 No Content` (ready) or `404` (no stock item) or `422 {reason: "packaging_data_missing"}` |
| `GET` | `/inventory/stock/{id}/packaging`| Internal | (Used by Delivery sync) Get physical dimensions. <br> **Res:** `200 OK {grossWeightGrams, lengthCm, widthCm, heightCm}` or `404` (no stock item) or `422 {reason: "packaging_data_missing"}` |
| `POST` | `/inventory/reservations` | Internal | (Used by Order sync) Hard reserve stock, all or nothing. <br> **Req:** `{orderId, items: [{productId, quantity}]}` (1–100 items, each `productId` once, `quantity ≥ 1`) <br> **Res:** `204 No Content` (Order Service owns the reservation TTL and finds reservations by `orderId`) or `400` or `409` (order already has active reservations; for a retry this means "already reserved") or `422 {insufficientItems: [{productId, requested, available}]}` |
| `POST` | `/inventory/reservations/{orderId}/release`| Internal| (Used by Order Saga/TTL) Release the order's active reservations; idempotent. <br> **Res:** `204 No Content` (also when nothing was active) |
| `GET` | `/inventory/stock` | `INVENTORY_WORKER`| View stock balances. <br> **Query:** `?packagingConfigured=true\|false&page=0&size=20&sortBy=field[,asc\|desc]` (`sortBy`: `productId`, `availableQuantity`, `reservedQuantity`) <br> **Res:** `200 OK {content: [{productId, availableQuantity, reservedQuantity, totalQuantity, packagingConfigured}], page, size, totalElements}` or `400` |
| `POST` | `/inventory/stock/{id}/adjust`| `INVENTORY_WORKER`| Update stock (+/- delta). <br> **Req:** `{delta}` (non-zero integer) <br> **Res:** `200 OK {newAvailableQuantity}` or `400` or `404` (product not in Catalog) or `422` (write-off below zero) or `503` (Catalog unavailable) |
| `PUT` | `/inventory/stock/{id}/packaging`| `INVENTORY_WORKER`| Set physical dimensions (all four replaced at once). <br> **Req:** `{grossWeightGrams, lengthCm, widthCm, heightCm}` (positive integers) <br> **Res:** `204 No Content` or `400` or `404` (product not in Catalog) or `503` (Catalog unavailable) |

---

## 5. Order Service

*Only registered customers can order (2026-10-16): every customer route requires `CUSTOMER`; the customer comes from `X-User-Id` (and the email from `X-User-Email`), and another customer's order answers `404`. No guest checkout, no `?token=` access.*

| Method | Path | Auth Role | Description |
|--------|------|-----------|-------------|
| `POST` | `/orders/validate` | `CUSTOMER` | Soft check cart for checkout eligibility. <br> **Req:** `{items: [{productId, quantity}]}` (1–100, no duplicates) <br> **Res:** `204 No Content` or `422 {ineligibleItems: [{productId, reason: NOT_ACTIVE \| INSUFFICIENT_STOCK}]}` or `503` |
| `POST` | `/orders` | `CUSTOMER` | Submit order & reserve stock. <br> **Header (optional):** `Idempotency-Key: <UUID>`, one per checkout attempt; a repeat with the same key and body returns the first order <br> **Req:** `{items, deliveryDetails: {name, phone, address, carrier}}` <br> **Res:** `201 Created {orderId, status, totalAmount, currency, reservationExpiresAt}` (a replay adds header `Idempotent-Replayed: true` and shows the order's current `status`) or `400` (also a non-UUID key) or `422 {ineligibleItems}` / `422 {insufficientItems}` / `422 {maxTotal}` (`order-total-too-large`) / `422` (`idempotency-key-mismatch`: key used with another body) or `409` (`request-in-progress`, with `Retry-After`) or `503` |
| `POST` | `/orders/{id}/pay` | `CUSTOMER` (owner) | Pay for the order. <br> **Req:** `{cardDetails: {number, expiryMonth, expiryYear, cvv}}` <br> **Res:** `200 OK {orderId, status}` or `402 {reason}` or `409` (not awaiting payment / window expired / attempt in progress) or `503` (retry with the same card is safe) |
| `GET` | `/orders` | `CUSTOMER` | View own order history. <br> **Query:** `?page=&size=` <br> **Res:** `200 OK {content: [{orderId, status, totalAmount, currency, placedAt}], page, size, totalElements}` |
| `GET` | `/orders/{id}` | `CUSTOMER` (owner) | View own order details. <br> **Res:** `200 OK {orderId, status, items, deliveryDetails, totalAmount, currency, placedAt, paidAt, reservationExpiresAt, ttn, deliveryStatus}` or `404` |
| `DELETE`| `/orders/{id}` | `CUSTOMER` (owner) | Cancel own order. <br> **Res:** `202 Accepted {orderId, status}` — `CANCELLED`, or `CANCELLATION_REQUESTED` while a saga step is retried later; `409` if not cancellable |
| `GET` | `/orders/fulfillment-queue` | `INVENTORY_WORKER`| Get `PAID` & `PENDING_SHIPPING` orders. (Order Service queries Delivery Service internally for TTN states). <br> **Res:** `200 OK {deliveryDataAvailable, orders: [{orderId, status, placedAt, paidAt, items, deliveryDetails, deliveryStatus, ttn, failureReason}]}` (oldest payment first; `deliveryDataAvailable: false` when Delivery is down) |
| `POST` | `/orders/{id}/ship` | `INVENTORY_WORKER`| Dispatch order to courier. <br> **Res:** `200 OK {orderId, status, ttn}` (Transitions to `SHIPPED`), `409` (not awaiting dispatch, or TTN not generated yet), `503` (Delivery unavailable, with `Retry-After`) |

---

## 6. Payment Service

*Acts as the Anti-Corruption Layer for payments. Commands are received internally from the Order Service Orchestrator.*

| Method | Path | Auth Role | Description |
|--------|------|-----------|-------------|
| `POST` | `/payments/charge` | Internal | Charge card. <br> **Req:** `{orderId, amount, currency: "UAH", cardDetails: {number, expiryMonth, expiryYear, cvv}}` <br> **Res:** `200 OK {transactionId, status}` or `400` (invalid) or `402 {reason}` (declined) or `409` (already charged / attempt in progress) or `502`/`504` (gateway; safe to repeat) |
| `POST` | `/payments/refund` | Internal | Refund order in full. <br> **Req:** `{orderId, amount, currency}` <br> **Res:** `200 OK {transactionId, status}` or `409` (already refunded) or `422` (no charge / partial amount) or `502`/`504` |

---

## 7. Delivery Service

| Method | Path | Auth Role | Description |
|--------|------|-----------|-------------|
| `GET` | `/delivery/shipments` | Internal | Batch fetch TTN statuses (Used by Order fulfillment queue). <br> **Query:** `?orderIds=uuid1,uuid2` (1–100) <br> **Res:** `200 OK [{orderId, status, ttn, failureReason}]` (`status: null` when no shipment exists yet; statuses `TTN_PENDING`, `TTN_FAILED`, `TTN_GENERATED`, `IN_TRANSIT`, `DELIVERED`, `CANCELLED`) |
| `DELETE`| `/delivery/shipments/{orderId}`| Internal | Cancel shipment / TTN via carrier (Used by Order Saga). Idempotent; carrier cancellation is best-effort. <br> **Res:** `204 No Content` or `409 Conflict` (already handed to the carrier or delivered) |
| `POST` | `/delivery/orders/{id}/ttn/retry`| `INVENTORY_WORKER`| Manually retry TTN generation (only in `TTN_FAILED`). <br> **Res:** `200 OK {ttn}` or `404` or `409 Conflict` or `424 Failed Dependency {reason}` |

Delivery has no public endpoint: the carrier is mocked in-process and reports deliveries through a Java interface, not a webhook (see the [Delivery spec](../services-specs/delivery-service-spec.md)).
