# REST API Contracts

> This document defines the synchronous HTTP API contracts for all microservices. 
> These are the endpoints that will be exposed to clients (via the API Gateway) or used for internal service-to-service communication.

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
| `GET` | `/customers/me/profile` | `CUSTOMER` | Get profile (lazy creation if missing). <br> **Res:** `200 OK {firstName, lastName, phone, defaultAddress}` |
| `PUT` | `/customers/me/profile` | `CUSTOMER` | Upsert profile. <br> **Req:** `{firstName, lastName, phone, defaultAddress}` <br> **Res:** `200 OK` |
| `GET` | `/customers/cart` | `CUSTOMER` | Get cart items. <br> **Res:** `200 OK [{productId, quantity}]` |
| `POST` | `/customers/cart/items` | `CUSTOMER` | Add item or increment quantity. <br> **Req:** `{productId, quantity}` <br> **Res:** `200 OK` (returns updated cart) |
| `PATCH`| `/customers/cart/items/{id}`| `CUSTOMER` | Update explicit quantity. <br> **Req:** `{quantity}` <br> **Res:** `200 OK` |
| `DELETE`|`/customers/cart/items/{id}`| `CUSTOMER` | Remove item from cart. <br> **Res:** `200 OK` |
| `POST` | `/customers/cart/merge` | `CUSTOMER` | Merge guest cart after login. <br> **Req:** `{items: [{productId, quantity}]}` <br> **Res:** `200 OK` |

---

## 3. Catalog Service

| Method | Path | Auth Role | Description |
|--------|------|-----------|-------------|
| `GET` | `/catalog/products` | None/`CATALOG_MANAGER`| Paginated search. Customers see only ACTIVE. Managers can filter by `status`. <br> **Query:** `search, categoryId, minPrice, maxPrice, sortBy (title\|basePrice[,asc\|desc]), status, page (zero-based), size (default 20, max 100)` <br> **Res:** `200 OK {content: [{productId, title, basePrice, categoryId, status, stockStatus}], page, totalElements}` |
| `GET` | `/catalog/products/{id}` | None | Get product details (ACTIVE only; inactive → same `404` as missing). <br> **Res:** `200 OK {productId, title, description, categoryId, basePrice, stockStatus}` |
| `POST` | `/catalog/products` | `CATALOG_MANAGER`| Create product in NOT_ACTIVE state. <br> **Req:** `{title, description, categoryId, basePrice}` <br> **Res:** `201 Created {productId}` |
| `PUT` | `/catalog/products/{id}` | `CATALOG_MANAGER`| Update product details. <br> **Req:** `{title, desc, categoryId, basePrice}` <br> **Res:** `200 OK` |
| `PATCH`| `/catalog/products/{id}/status`| `CATALOG_MANAGER`| Activate/Deactivate product. <br> **Req:** `{status}` <br> **Res:** `200 OK` or `422 Unprocessable` (if activation fails readiness check) |
| `GET` | `/catalog/categories` | None | List/search categories (EP-CAT-10, added to the original spec). <br> **Query:** `search, page (zero-based), size (default 20, max 100)` <br> **Res:** `200 OK {content: [{categoryId, name}], page, totalElements}` |
| `POST` | `/catalog/categories` | `CATALOG_MANAGER`| Create category. <br> **Req:** `{name}` <br> **Res:** `201 Created {categoryId}` |
| `PUT` | `/catalog/categories/{id}`| `CATALOG_MANAGER`| Rename category. <br> **Req:** `{name}` <br> **Res:** `200 OK` |
| `DELETE`|`/catalog/categories/{id}`| `CATALOG_MANAGER`| Delete category. <br> **Res:** `200 OK` or `409 Conflict` (if products attached) |
| `GET` | `/catalog/products/batch-get`| Internal | Fetch current product data for Order Service snapshotting and for Inventory's existence check. <br> **Query:** `?ids=uuid1,uuid2` (1–100 IDs) <br> **Res:** `200 OK [{productId, title, basePrice, status}]` — a flat array of the products that exist, in any status; unknown IDs are simply absent (the spec's `notFound` list was dropped on purpose). `400` for empty, malformed or too many `ids`. |

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

| Method | Path | Auth Role | Description |
|--------|------|-----------|-------------|
| `POST` | `/orders/validate` | None/`CUSTOMER`| Soft check cart for checkout eligibility. <br> **Req:** `{items: [{productId, quantity}]}` <br> **Res:** `200 OK` or `422 {ineligibleItems}` |
| `POST` | `/orders` | None/`CUSTOMER`| Submit order & reserve stock. <br> **Req:** `{items, deliveryDetails, contactEmail}` <br> **Res:** `201 Created {orderId, orderAccessToken}` |
| `POST` | `/orders/{id}/pay` | None/`CUSTOMER`| Initiate payment. <br> **Req:** `{cardDetails}` <br> **Res:** `200 OK` or `402 Payment Required` or `409 Conflict` (Expired) |
| `GET` | `/orders` | `CUSTOMER` | View order history. <br> **Res:** `200 OK [{orderId, status, totalAmount, placedAt}]` |
| `GET` | `/orders/{id}` | None/`CUSTOMER`| View details (Auth via JWT or `?token=`). <br> **Res:** `200 OK {orderId, items, status, ttn, ...}` |
| `DELETE`| `/orders/{id}` | None/`CUSTOMER`| Request cancellation. <br> **Res:** `202 Accepted` (Transitions to `CANCELLATION_REQUESTED` and starts Saga) |
| `GET` | `/orders/fulfillment-queue` | `INVENTORY_WORKER`| Get `PAID` & `PENDING_SHIPPING` orders. (Order Service queries Delivery Service internally for TTN states). <br> **Res:** `200 OK [{orderId, status, deliveryStatus, ttn}]` |
| `POST` | `/orders/{id}/ship` | `INVENTORY_WORKER`| Dispatch order to courier. <br> **Res:** `200 OK` (Transitions to `SHIPPED`) |

---

## 6. Payment Service

*Acts as the Anti-Corruption Layer for payments. Commands are received internally from the Order Service Orchestrator.*

| Method | Path | Auth Role | Description |
|--------|------|-----------|-------------|
| `POST` | `/payments/charge` | Internal | Charge card. <br> **Req:** `{orderId, amount, currency, cardDetails}` <br> **Res:** `200 OK` or `402 Payment Failed` |
| `POST` | `/payments/refund` | Internal | Refund order. <br> **Req:** `{orderId, amount, currency}` <br> **Res:** `200 OK` |

---

## 7. Delivery Service

| Method | Path | Auth Role | Description |
|--------|------|-----------|-------------|
| `GET` | `/delivery/shipments` | Internal | Batch fetch TTN statuses (Used by Order fulfillment queue). <br> **Query:** `?orderIds=uuid1,uuid2` (1–100) <br> **Res:** `200 OK [{orderId, status, ttn, failureReason}]` (`status: null` when no shipment exists yet; statuses `TTN_PENDING`, `TTN_FAILED`, `TTN_GENERATED`, `IN_TRANSIT`, `DELIVERED`, `CANCELLED`) |
| `DELETE`| `/delivery/shipments/{orderId}`| Internal | Cancel shipment / TTN via carrier (Used by Order Saga). Idempotent; carrier cancellation is best-effort. <br> **Res:** `204 No Content` or `409 Conflict` (already handed to the carrier or delivered) |
| `POST` | `/delivery/orders/{id}/ttn/retry`| `INVENTORY_WORKER`| Manually retry TTN generation (only in `TTN_FAILED`). <br> **Res:** `200 OK {ttn}` or `404` or `409 Conflict` or `424 Failed Dependency {reason}` |

Delivery has no public endpoint: the carrier is mocked in-process and reports deliveries through a Java interface, not a webhook (see the [Delivery spec](../services-specs/delivery-service-spec.md)).
