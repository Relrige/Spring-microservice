# Event Catalogue

> This document defines the asynchronous Domain Events published to the message broker (RabbitMQ / Kafka).
> These events facilitate decoupling for non-blocking side-effects.
> *Note: With Orchestration selected for Sagas, workflow commands (like "Release Stock" or "Refund") are handled via synchronous REST calls rather than events.*

---

## 1. Catalog & Inventory Synchronization

> **Conventions for this section:** every event carries a mandatory `eventId` (UUID, unique per event) so consumers can deduplicate at-least-once deliveries. The Kafka message key is `productId`; `ProductStockReplenished` and `ProductStockDepleted` share one topic so that events of one product keep their order. Consumers ignore unknown payload fields.
>
> **Stock events topic:** `inventory.stock-events`, owned and declared by Inventory Service (3 partitions). Both stock events have the same payload shape, so each record carries a Kafka header **`eventType`** with the value `ProductStockReplenished` or `ProductStockDepleted`; consumers choose the event type from this header. Records are JSON without Java type headers. Inventory publishes after the database commit; until the transactional outbox is in place, an event can be lost if the broker is down at that moment.
>
> **`ProductCreated` topic:** owned and declared by Catalog Service; consumers take its name from configuration.

### `ProductCreated`
- **Producer:** Catalog Service
- **Consumers:** Inventory Service
- **Trigger:** A new product is created by the Catalog Manager.
- **Topic / key:** `catalog.product-events` (3 partitions), message key = `productId`; header `eventType: ProductCreated`.
- **Payload:**
  ```json
  {
    "eventId": "uuid",
    "productId": "uuid",
    "timestamp": "2023-10-01T12:00:00Z"
  }
  ```
  `eventId` is unique per event, so consumers can deduplicate redelivered messages (it is also sent as the `eventId` header). `timestamp` is an ISO-8601 UTC instant.
- **Delivery:** at-least-once, through Catalog's transactional outbox. A consumer may receive the same event more than once and must ignore repeats by `eventId`.

### `ProductStockReplenished`
- **Producer:** Inventory Service
- **Consumers:** Catalog Service
- **Trigger:** `availableQuantity` of a product transitions from `0` to `> 0` (any product, regardless of its Catalog status): a restock (EP-INV-02) or a reservation release (EP-INV-07).
- **Topic / key:** `inventory.stock-events` (3 partitions), message key = `productId`; header `eventType: ProductStockReplenished`. Shared with `ProductStockDepleted`: one topic and one key per product keep both event types in order, so a delayed `Depleted` can never overtake a later `Replenished`. Catalog sends messages it cannot process to its dead-letter topic `catalog.stock-events-dlt`.
- **Payload:**
  ```json
  {
    "eventId": "uuid",
    "productId": "uuid",
    "timestamp": "2023-10-01T12:00:00Z"
  }
  ```

### `ProductStockDepleted`
- **Producer:** Inventory Service
- **Consumers:** Catalog Service
- **Trigger:** `availableQuantity` of a product transitions from `> 0` to `0` (any product, regardless of its Catalog status): a write-off (EP-INV-02) or a reservation (EP-INV-06). Shipping an order (`OrderShipped`) does not emit it, because shipping does not change `availableQuantity`.
- **Topic / key:** `inventory.stock-events` (same topic as `ProductStockReplenished`), message key = `productId`; header `eventType: ProductStockDepleted`.
- **Payload:**
  ```json
  {
    "eventId": "uuid",
    "productId": "uuid",
    "timestamp": "2023-10-01T12:00:00Z"
  }
  ```

---

## 2. Order Lifecycle

### `OrderPlaced`
- **Producer:** Order Service
- **Consumers:** Notification Service
- **Trigger:** A new order is submitted and stock is reserved (`PENDING_PAYMENT`).
- **Payload:**
  ```json
  {
    "orderId": "uuid",
    "customerEmail": "user@example.com",
    "totalAmount": 1500.00,
    "currency": "UAH"
  }
  ```

### `OrderPaid`
- **Producer:** Order Service
- **Consumers:** Customer Service, Delivery Service, Notification Service
- **Trigger:** Successful payment is confirmed.
- **Consumer Actions:**
  - *Customer:* Clears the backend cart.
  - *Delivery:* Initiates asynchronous TTN generation with the carrier.
  - *Notification:* Sends receipt email.
- **Payload:**
  ```json
  {
    "orderId": "uuid",
    "customerId": "uuid (nullable)",
    "customerEmail": "user@example.com"
  }
  ```

### `OrderShipped`
- **Producer:** Order Service
- **Consumers:** Inventory Service, Notification Service
- **Trigger:** Inventory worker dispatches the parcel.
- **Consumer Actions:**
  - *Inventory:* Permanently deducts `reservedQuantity` (consumes the reservation).
  - *Notification:* Sends tracking email.
- **Payload:**
  ```json
  {
    "orderId": "uuid",
    "ttn": "CARRIER-123456789",
    "customerEmail": "user@example.com"
  }
  ```

### `OrderCancelled`
- **Producer:** Order Service
- **Consumers:** Notification Service
- **Trigger:** Compensation saga completes or TTL expires.
- **Payload:**
  ```json
  {
    "orderId": "uuid",
    "customerEmail": "user@example.com",
    "reason": "CUSTOMER_REQUESTED | EXPIRED"
  }
  ```

---

## 3. Delivery Updates

### `TTNGenerated`
- **Producer:** Delivery Service
- **Consumers:** Order Service
- **Trigger:** Delivery successfully registers the parcel with the carrier gateway.
- **Consumer Actions:**
  - *Order:* Transitions status from `PAID` to `PENDING_SHIPPING`.
- **Payload:**
  ```json
  {
    "orderId": "uuid",
    "ttn": "CARRIER-123456789"
  }
  ```

### `OrderDelivered`
- **Producer:** Delivery Service
- **Consumers:** Order Service, Notification Service
- **Trigger:** Carrier webhook confirms the parcel reached the customer.
- **Consumer Actions:**
  - *Order:* Transitions status to `DELIVERED`.
- **Payload:**
  ```json
  {
    "orderId": "uuid",
    "ttn": "CARRIER-123456789"
  }
  ```
