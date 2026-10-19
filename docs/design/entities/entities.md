# Domain Entities

> Derived from system scenarios, technical insights, and design decisions.
> This document outlines the primary data entities (Aggregates) owned by each microservice.
> *Note: These are logical data models, not exact JPA/SQL schemas.*

---

## 1. Auth Service

Owns authentication credentials and system roles.

### `User`
- `id`: UUID (Primary Key)
- `email`: String (Unique)
- `passwordHash`: String
- `role`: Enum `[CUSTOMER, CATALOG_MANAGER, INVENTORY_WORKER, ADMIN]`
- `createdAt`: Timestamp

---

## 2. Customer Service

Owns customer profile data and cart management. Separated from Auth to keep Identity independent of e-commerce concerns.

### `CustomerProfile`
- `customerId`: UUID (Primary Key, matches Auth `User.id`)
- `firstName`: String
- `lastName`: String
- `phone`: String
- `defaultAddress`: String
- `createdAt`: Timestamp
- `updatedAt`: Timestamp

### `Cart` (Aggregate Root)
- `customerId`: UUID (Primary Key, matches Auth `User.id`)
- `items`: List of `CartItem` (Value Objects / Child Entities)

**`CartItem`**
- `productId`: UUID
- `quantity`: Integer

*(Note: Cart does not store product names or snapshots; prices and availability are dynamically resolved via Catalog Service during rendering).*

---

## 3. Catalog Service

Owns the product catalog, categorisation, and current active prices.

### `Category`
- `id`: UUID (Primary Key)
- `name`: String (Unique)

### `Product`
- `id`: UUID (Primary Key)
- `title`: String
- `description`: String
- `categoryId`: UUID (Foreign Key)
- `basePrice`: Decimal
- `status`: Enum `[ACTIVE, NOT_ACTIVE]` (Determined by Catalog Manager)
- `stockStatus`: Enum `[IN_STOCK, OUT_OF_STOCK]` *(Read-model field updated asynchronously via `ProductStockReplenished` / `ProductStockDepleted` events from Inventory)*

---

## 4. Inventory Service

Owns stock counts, physical dimensions (for shipping), and stock reservations.

### `StockItem`
- `productId`: UUID (Primary Key, matches Catalog `Product.id`)
- `availableQuantity`: Integer
- `reservedQuantity`: Integer *(Stored, not computed. The service updates it together with `availableQuantity` in the same transaction whenever a reservation is created, released or consumed.)*
- `grossWeightGrams`: Integer (Nullable until configured; `> 0` when set)
- `lengthCm`: Integer (Nullable until configured; `> 0` when set)
- `widthCm`: Integer (Nullable until configured; `> 0` when set)
- `heightCm`: Integer (Nullable until configured; `> 0` when set)
- *Constraint:* `availableQuantity` and `reservedQuantity` are never negative.
- *Derived, not stored:* `totalQuantity = availableQuantity + reservedQuantity`; `packagingConfigured` = all four packaging values set and `> 0`.

### `StockItemReservation`
One row per product line of an order (an order has 1+ rows). Rows are history and are never deleted. There is no expiry: the payment-window TTL is `Order.reservationExpiresAt`.
- `id`: UUID (Primary Key, generated)
- `orderId`: UUID
- `productId`: UUID (Foreign Key to `StockItem.productId`)
- `quantity`: Integer (`≥ 1`)
- `status`: Enum `[ACTIVE, RELEASED, CONSUMED]` (`RELEASED` and `CONSUMED` are terminal)
- `createdAt`: Timestamp
- `updatedAt`: Timestamp *(when the reservation was released or consumed)*
- *Constraint:* at most one `ACTIVE` reservation per `(orderId, productId)`.

---

## 5. Order Service

Owns the order lifecycle, immutable historical snapshots of purchases, and coordinates the checkout flow.

### `Order` (Aggregate Root)
- `id`: UUID (Primary Key)
- `customerId`: UUID (never null: only registered customers can order, decision 2026-10-16; there is no guest checkout)
- `customerEmail`: String (snapshot of the account email at placement, from `X-User-Email`; used by the order events)
- `status`: Enum `[PENDING_PAYMENT, PAID, PENDING_SHIPPING, SHIPPED, DELIVERED, CANCELLATION_REQUESTED, CANCELLED, EXPIRED]`
- `totalAmount`: Decimal
- `currency`: String (e.g., "UAH")
- `deliveryName`: String
- `deliveryPhone`: String
- `deliveryAddress`: String
- `deliveryCarrier`: String
- `reservationExpiresAt`: Timestamp
- `placedAt`: Timestamp
- `paidAt`: Timestamp (Nullable until paid)
- `statusBeforeCancellation`: Enum (Nullable; set when cancellation starts, decides whether a refund is owed)
- `cancellationAttempts`, `cancellationFailure`, `cancellationLastAttemptAt`, `needsManualAttention`: technical fields of an interrupted cancellation saga (the last-attempt time drives the retry back-off)
- `version`: optimistic-locking version (technical)
- `items`: List of `OrderItemSnapshot`

**`OrderItemSnapshot`** (Child Entity)
- `id`: UUID
- `productId`: UUID
- `productTitle`: String *(Historical snapshot of the Catalog title)*
- `unitPriceAtOrder`: Decimal *(Historical snapshot)*
- `quantity`: Integer

*(Note: Delivery/TTN statuses are NOT denormalised into the Order entity. Order Service makes a synchronous batch request to Delivery Service to fetch delivery data when rendering the fulfillment queue).*

---

## 6. Payment Service

An Anti-Corruption Layer (ACL) for the external payment gateway. Owns transaction histories.

### `PaymentTransaction`
- `id`: UUID (Primary Key)
- `orderId`: UUID
- `type`: Enum `[CHARGE, REFUND]`
- `status`: Enum `[SUCCESS, FAILED]`
- `amount`: Decimal
- `currency`: String (ISO 4217, e.g., "UAH")
- `gatewayTransactionId`: String (Nullable, returned by external gateway; always set on `SUCCESS`)
- `failureReason`: Enum `[INSUFFICIENT_FUNDS, CARD_DECLINED, REFUND_REJECTED, GATEWAY_TIMEOUT, GATEWAY_UNAVAILABLE]` (Nullable, set exactly on `FAILED`). The first three are definitive (the gateway said no); the last two are uncertain (no answer, the money may have moved)
- `idempotencyKey`: String (the key the request was sent to the gateway with; a repeat with the same key is processed once)
- `createdAt`: Timestamp

Rows are append-only: a refund is a new `REFUND` row, never a change of the charge. At most one `SUCCESS` row per order and type.

---

## 7. Delivery Service

An Anti-Corruption Layer (ACL) for the external courier/shipping gateways. Owns TTNs and dispatch states.

### `Shipment`
- `id`: UUID (Primary Key)
- `orderId`: UUID (Unique — one shipment per order)
- `status`: Enum `[TTN_PENDING, TTN_FAILED, TTN_GENERATED, IN_TRANSIT, DELIVERED, CANCELLED]`
- `ttn`: String (Nullable until generated by gateway)
- `failureReason`: String (Nullable, stores gateway / packaging error messages for worker visibility)
- `recipientName`: String (Nullable only for a `CANCELLED` tombstone, see the Delivery spec)
- `recipientPhone`: String (same)
- `recipientAddress`: String (same)
- `carrier`: String (same; from the order's `deliveryDetails.carrier`)
- `items`: List of `ShipmentItem` (`productId`: UUID, `quantity`: Integer) — copied from `OrderPaid`, needed to re-fetch packaging on a retry
- `totalWeightGrams`: Integer (Nullable until computed)
- `lengthCm`: Integer (Nullable until computed — stacking heuristic, see the Delivery spec)
- `widthCm`: Integer (same)
- `heightCm`: Integer (same)
- `declaredValue`: Decimal *(the order's `totalAmount`)*
- `currency`: String
- `handedOverAt`: Timestamp (Nullable until `OrderShipped` moves the shipment to `IN_TRANSIT`)
- `createdAt`: Timestamp
- `updatedAt`: Timestamp

*(Technical columns `attempts`, `nextAttemptAt` and `version` support automatic TTN generation and optimistic locking; they are not part of the domain model.)*

---

## 8. Notification Service

Purely event-driven.
Does not own any persistent domain entities. Its own database holds two technical tables (see the [Notification spec](../services-specs/notification-service-spec.md)):
- `notifications`: one row per email to send (`eventId` as dedup key, recipient, rendered subject and body, `status` `PENDING` / `SENT` / `FAILED`, attempts and next attempt time).
- `order_recipients`: a read model `orderId → customerEmail` filled from the order events, used for Delivery's `OrderDelivered`, which carries no email.
