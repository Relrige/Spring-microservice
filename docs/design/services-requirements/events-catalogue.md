# Event Catalogue

> This document defines the asynchronous Domain Events published to the message broker (RabbitMQ / Kafka).
> These events facilitate decoupling for non-blocking side-effects.
> *Note: With Orchestration selected for Sagas, workflow commands (like "Release Stock" or "Refund") are handled via synchronous REST calls rather than events.*

---

## 1. Catalog & Inventory Synchronization

> **Conventions for this section:** every event carries a mandatory `eventId` (UUID, unique per event) so consumers can deduplicate at-least-once deliveries. The Kafka message key is `productId`; `ProductStockReplenished` and `ProductStockDepleted` share one topic so that events of one product keep their order. Consumers ignore unknown payload fields.
>
> **Stock events topic:** `inventory.stock-events`, owned and declared by Inventory Service (3 partitions). Both stock events have the same payload shape, so each record carries a Kafka header **`eventType`** with the value `ProductStockReplenished` or `ProductStockDepleted`; consumers choose the event type from this header. Each record also carries the header **`eventId`** (the same value as in the payload). Records are JSON without Java type headers. **Delivery:** at-least-once, through Inventory's transactional outbox: the event is stored in the same transaction as the stock change and published by a relay afterwards, so a broker outage only delays it. Events of one product are published in the order of their stock changes, also with several Inventory replicas. A consumer may receive an event more than once and must ignore repeats by `eventId` or be idempotent by state (as Catalog's `stockStatus` update is).
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
- **Inventory consumer:** group `inventory-service`, Idempotent Consumer (`processed_events` keyed by `eventId`), non-blocking retries `catalog.product-events-inventory-retry-0/1/2` and DLT `catalog.product-events-inventory-dlt` (owned by Inventory). See the [fault-tolerance report](../../reports/messaging-fault-tolerance.md).

### `ProductStockReplenished`
- **Producer:** Inventory Service
- **Consumers:** Catalog Service
- **Trigger:** `availableQuantity` of a product transitions from `0` to `> 0` (any product, regardless of its Catalog status): a restock (EP-INV-02) or a reservation release (EP-INV-07).
- **Topic / key:** `inventory.stock-events` (3 partitions), message key = `productId`; headers `eventType: ProductStockReplenished` and `eventId`. Shared with `ProductStockDepleted`: one topic and one key per product keep both event types in order, so a delayed `Depleted` can never overtake a later `Replenished`. Catalog sends messages it cannot process to its dead-letter topic `catalog.stock-events-dlt`.
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
- **Topic / key:** `inventory.stock-events` (same topic as `ProductStockReplenished`), message key = `productId`; headers `eventType: ProductStockDepleted` and `eventId`.
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
- **Topic / key:** provisionally `order.order-events` (key `orderId`), header `eventType: OrderPaid` (and `eventId`), like `OrderShipped`.
- **Consumer Actions:**
  - *Customer:* Clears the backend cart.
  - *Delivery:* Creates a `Shipment` (`TTN_PENDING`); its TTN generation job then registers the parcel with the carrier.
  - *Notification:* Sends receipt email.
- **Payload** (event-carried state transfer, decided 2026-10-15 in the [Delivery spec](../services-specs/delivery-service-spec.md)): the event carries everything Delivery needs to register the shipment, so Delivery never calls Order back. Consumers read only the fields they need and ignore the rest.
  ```json
  {
    "eventId": "uuid",
    "orderId": "uuid",
    "customerId": "uuid (nullable)",
    "customerEmail": "user@example.com",
    "deliveryDetails": { "name": "Ivan Petrenko", "phone": "+380501234567", "address": "Kyiv, branch 12", "carrier": "NOVA_POSHTA" },
    "items": [ { "productId": "uuid", "quantity": 2 } ],
    "totalAmount": 1500.00,
    "currency": "UAH",
    "paidAt": "2026-10-15T12:00:00Z"
  }
  ```
  `deliveryDetails` and `items` are copied from the order's snapshot; `totalAmount` becomes the shipment's declared value. The topic therefore contains personal data of the recipient.

### `OrderShipped`
- **Producer:** Order Service
- **Consumers:** Inventory Service, Delivery Service, Notification Service
- **Trigger:** Inventory worker dispatches the parcel.
- **Topic / key:** owned by Order Service; provisionally `order.order-events` (key `orderId`). Every message must carry the header `eventType: OrderShipped`: the topic holds all order events, and Inventory consumes only records with this header.
- **Consumer Actions:**
  - *Inventory:* Permanently deducts `reservedQuantity` (consumes the reservation).
  - *Delivery:* Marks the shipment `IN_TRANSIT` and hands the parcel to the carrier, which later reports the delivery.
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

> **Conventions for this section:** topic `delivery.shipment-events`, owned and declared by Delivery Service (3 partitions), message key = `orderId`. Each record carries the headers `eventType` (`TTNGenerated` or `OrderDelivered`) and `eventId`. Payloads are JSON without Java type headers and carry a mandatory `eventId` and an ISO-8601 UTC `timestamp`. **Delivery:** at-least-once, through Delivery's transactional outbox; consumers must be idempotent (Order's consumers are idempotent by state).

### `TTNGenerated`
- **Producer:** Delivery Service
- **Consumers:** Order Service
- **Trigger:** Delivery successfully registers the parcel with the carrier gateway (automatic generation or a worker's manual retry).
- **Topic / key:** `delivery.shipment-events`, key `orderId`, header `eventType: TTNGenerated`.
- **Consumer Actions:**
  - *Order:* Transitions status from `PAID` to `PENDING_SHIPPING`.
- **Payload:**
  ```json
  {
    "eventId": "uuid",
    "orderId": "uuid",
    "ttn": "20450012345678",
    "timestamp": "2026-10-15T12:00:05Z"
  }
  ```

### `OrderDelivered`
- **Producer:** Delivery Service
- **Consumers:** Order Service, Notification Service
- **Trigger:** The carrier reports that the parcel reached the customer. While the carrier is mocked in-process, the mock reports it a configurable delay after the handover (`OrderShipped`). Emitted once per shipment.
- **Topic / key:** `delivery.shipment-events`, key `orderId`, header `eventType: OrderDelivered`.
- **Consumer Actions:**
  - *Order:* Transitions status to `DELIVERED`.
- **Payload:**
  ```json
  {
    "eventId": "uuid",
    "orderId": "uuid",
    "ttn": "20450012345678",
    "timestamp": "2026-10-16T09:30:00Z"
  }
  ```
  No `customerEmail`: customer contact data is not Delivery's responsibility. Notification Service resolves the recipient itself (from Auth or Customer Service); that is designed with Notification.
