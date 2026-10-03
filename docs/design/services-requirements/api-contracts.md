# REST API Contracts

> This document defines the synchronous HTTP API contracts for all microservices. 
> These are the endpoints that will be exposed to clients (via the API Gateway) or used for internal service-to-service communication.

---

## 1. Auth Service

| Method | Path | Auth Role | Description |
|--------|------|-----------|-------------|
| `POST` | `/auth/register` | None | Register a new customer. <br> **Req:** `{email, password}` <br> **Res:** `201 Created {userId}` |
| `POST` | `/auth/login` | None | Authenticate and get JWT. <br> **Req:** `{email, password}` <br> **Res:** `200 OK {token}` |

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
| `GET` | `/catalog/products` | None/`CATALOG_MGR`| Paginated search. Customers see only ACTIVE. Managers can filter by `status`. <br> **Query:** `categoryId, search, minPrice, status, ...` <br> **Res:** `200 OK [{id, title, basePrice, stockStatus}]` |
| `GET` | `/catalog/products/{id}` | None | Get product details (ACTIVE only). <br> **Res:** `200 OK {id, title, desc, basePrice, stockStatus}` |
| `POST` | `/catalog/products` | `CATALOG_MGR`| Create product in NOT_ACTIVE state. <br> **Req:** `{title, description, categoryId, basePrice}` <br> **Res:** `201 Created {productId}` |
| `PUT` | `/catalog/products/{id}` | `CATALOG_MGR`| Update product details. <br> **Req:** `{title, desc, categoryId, basePrice}` <br> **Res:** `200 OK` |
| `PATCH`| `/catalog/products/{id}/status`| `CATALOG_MGR`| Activate/Deactivate product. <br> **Req:** `{status}` <br> **Res:** `200 OK` or `422 Unprocessable` (if activation fails readiness check) |
| `POST` | `/catalog/categories` | `CATALOG_MGR`| Create category. <br> **Req:** `{name}` <br> **Res:** `201 Created {categoryId}` |
| `PUT` | `/catalog/categories/{id}`| `CATALOG_MGR`| Rename category. <br> **Req:** `{name}` <br> **Res:** `200 OK` |
| `DELETE`|`/catalog/categories/{id}`| `CATALOG_MGR`| Delete category. <br> **Res:** `200 OK` or `409 Conflict` (if products attached) |
| `GET` | `/catalog/products/batch-get`| Internal | Fetch current prices for Order Service snapshotting. <br> **Query:** `?ids=uuid1,uuid2` <br> **Res:** `200 OK [{productId, price}]` |

---

## 4. Inventory Service

| Method | Path | Auth Role | Description |
|--------|------|-----------|-------------|
| `GET` | `/inventory/stock/{id}/readiness`| Internal | (Used by Catalog sync) Check if packaging is configured. <br> **Res:** `200 OK` or `422 Unprocessable {reason}` |
| `GET` | `/inventory/stock/{id}/packaging`| Internal | (Used by Delivery sync) Get physical dimensions. <br> **Res:** `200 OK {totalWeightGrams, lengthCm, ...}` |
| `POST` | `/inventory/reservations` | Internal | (Used by Order sync) Hard reserve stock. <br> **Req:** `{orderId, items: [{productId, quantity}]}` <br> **Res:** `200 OK` or `422` (Insufficient stock) |
| `POST` | `/inventory/reservations/{orderId}/release`| Internal| (Used by Order Saga/TTL) Release reservations. <br> **Res:** `200 OK` |
| `GET` | `/inventory/stock` | `INVENTORY_W`| View stock balances. <br> **Res:** `200 OK [{productId, availableQuantity, ...}]` |
| `POST` | `/inventory/stock/{id}/adjust`| `INVENTORY_W`| Update stock (+/- delta). <br> **Req:** `{delta}` <br> **Res:** `200 OK {newAvailableQuantity}` |
| `PUT` | `/inventory/stock/{id}/packaging`| `INVENTORY_W`| Set physical dimensions. <br> **Req:** `{grossWeightGrams, lengthCm, widthCm, heightCm}` <br> **Res:** `200 OK` |

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
| `GET` | `/orders/fulfillment-queue` | `INVENTORY_W`| Get `PAID` & `PENDING_SHIPPING` orders. (Order Service queries Delivery Service internally for TTN states). <br> **Res:** `200 OK [{orderId, status, deliveryStatus, ttn}]` |
| `POST` | `/orders/{id}/ship` | `INVENTORY_W`| Dispatch order to courier. <br> **Res:** `200 OK` (Transitions to `SHIPPED`) |

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
| `GET` | `/delivery/shipments` | Internal | Batch fetch TTN statuses (Used by Order fulfillment queue). <br> **Query:** `?orderIds=uuid1,uuid2` <br> **Res:** `200 OK [{orderId, status, ttn, failureReason}]` |
| `DELETE`| `/delivery/shipments/{orderId}`| Internal | Cancel TTN via carrier (Used by Order Saga). <br> **Res:** `200 OK` |
| `POST` | `/delivery/orders/{id}/ttn/retry`| `INVENTORY_W`| Manually retry TTN generation. <br> **Res:** `200 OK` or `424 Failed Dependency` |
| `POST` | `/delivery/carrier-webhook` | None | Carrier delivery confirmation. <br> **Req:** `{orderId, ttn, status}` <br> **Res:** `200 OK` |
