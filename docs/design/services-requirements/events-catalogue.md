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
> **Product events topic:** `catalog.product-events`, owned and declared by Catalog Service; consumers take its name from configuration. It carries `ProductCreated` and `ProductUpdated`, told apart by the header `eventType`; each record also carries the header `eventId`. Both payloads are the **full product state after the change** (without `description` and `stockStatus`), including the product's `version` (revision 2026-10-22, Catalog spec D5–D6). **Order is not guaranteed** (the outbox relay may run on several replicas, and retry topics reorder): a consumer applies an event only if its `version` is greater than the one it stored, which also makes repeats harmless.

### `ProductCreated`
- **Producer:** Catalog Service
- **Consumers:** Inventory Service
- **Trigger:** A new product is created by the Catalog Manager (EP-CAT-01).
- **Topic / key:** `catalog.product-events` (3 partitions), message key = `productId`; headers `eventType: ProductCreated` and `eventId`.
- **Payload:**
  ```json
  {
    "eventId": "uuid",
    "productId": "uuid",
    "title": "Phone X",
    "categoryId": "uuid",
    "basePrice": 499.99,
    "status": "NOT_ACTIVE",
    "version": 0,
    "timestamp": "2023-10-01T12:00:00Z"
  }
  ```
  `eventId` is unique per event, so consumers can deduplicate redelivered messages (it is also sent as the `eventId` header). `timestamp` is an ISO-8601 UTC instant. `title`, `categoryId`, `basePrice`, `status` and `version` were added on 2026-10-22 (additive); events published earlier lack them, so consumers treat them as optional here. A new product is always `NOT_ACTIVE` with `version: 0`.
- **Delivery:** at-least-once, through Catalog's transactional outbox. A consumer may receive the same event more than once and must ignore repeats by `eventId` or by `version`.
- **Inventory consumer:** group `inventory-service`, Idempotent Consumer (`processed_events` keyed by `eventId`), creates the stock row and stores the copy of title, status and version ("newer version wins"); non-blocking retries `catalog.product-events-inventory-retry-0/1/2` and DLT `catalog.product-events-inventory-dlt` (owned by Inventory). See the [fault-tolerance report](../../reports/messaging-fault-tolerance.md) and EP-INV-EVT-01.

### `ProductUpdated`
- **Producer:** Catalog Service
- **Consumers:** Inventory Service
- **Trigger:** A catalog manager updates a product (EP-CAT-02, every successful call) or changes its status (EP-CAT-03, only when the status actually changes).
- **Topic / key:** `catalog.product-events` (same topic as `ProductCreated`), message key = `productId`; headers `eventType: ProductUpdated` and `eventId`.
- **Payload:** the same shape as `ProductCreated`; all fields are mandatory.
  ```json
  {
    "eventId": "uuid",
    "productId": "uuid",
    "title": "Phone X Pro",
    "categoryId": "uuid",
    "basePrice": 549.99,
    "status": "ACTIVE",
    "version": 3,
    "timestamp": "2023-10-02T09:30:00Z"
  }
  ```
  `version` is the product's version after the change. An EP-CAT-02 call that changed nothing repeats the stored version.
- **Delivery:** at-least-once, through Catalog's transactional outbox.
- **Inventory consumer:** group `inventory-service`, the same listener, retries and DLT as `ProductCreated`; idempotent by state (the version check), so no `processed_events` key. A missing stock row is created. A record without `title`, `status` or `version` goes to the DLT without retries. See EP-INV-EVT-03.

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

> **Conventions for this section** (aligned 2026-10-16 with the [Order spec](../services-specs/order-service-spec.md)): topic `order.order-events`, owned and declared by Order Service (3 partitions, name from `ORDER_EVENTS_TOPIC`), message key = `orderId`. Each record carries the headers `eventType` (`OrderPlaced`, `OrderPaid`, `OrderShipped` or `OrderCancelled`) and `eventId`. Payloads are JSON without Java type headers and carry a mandatory `eventId` and an ISO-8601 UTC `timestamp`. **Delivery:** at-least-once, through Order's transactional outbox; consumers must be idempotent and ignore event types they do not handle. Only registered customers can order, so `customerId` and `customerEmail` are always present.

### `OrderPlaced`
- **Producer:** Order Service
- **Consumers:** Notification Service
- **Trigger:** A new order is submitted and stock is reserved (`PENDING_PAYMENT`).
- **Payload:**
  ```json
  {
    "eventId": "uuid",
    "orderId": "uuid",
    "customerId": "uuid",
    "customerEmail": "user@example.com",
    "totalAmount": 1500.00,
    "currency": "UAH",
    "reservationExpiresAt": "2026-10-16T12:15:00Z",
    "timestamp": "2026-10-16T12:00:00Z"
  }
  ```

### `OrderPaid`
- **Producer:** Order Service
- **Consumers:** Customer Service, Delivery Service, Notification Service
- **Trigger:** Successful payment is confirmed (`PENDING_PAYMENT → PAID`).
- **Consumer Actions:**
  - *Customer:* Clears the backend cart.
  - *Delivery:* Creates a `Shipment` (`TTN_PENDING`); its TTN generation job then registers the parcel with the carrier.
  - *Notification:* Sends receipt email.
- **Payload** (event-carried state transfer, decided 2026-10-15 in the [Delivery spec](../services-specs/delivery-service-spec.md)): the event carries everything Delivery needs to register the shipment, so Delivery never calls Order back. Consumers read only the fields they need and ignore the rest.
  ```json
  {
    "eventId": "uuid",
    "orderId": "uuid",
    "customerId": "uuid",
    "customerEmail": "user@example.com",
    "deliveryDetails": { "name": "Ivan Petrenko", "phone": "+380501234567", "address": "Kyiv, branch 12", "carrier": "NOVA_POSHTA" },
    "items": [ { "productId": "uuid", "quantity": 2 } ],
    "totalAmount": 1500.00,
    "currency": "UAH",
    "paidAt": "2026-10-15T12:00:00Z",
    "timestamp": "2026-10-15T12:00:00Z"
  }
  ```
  `deliveryDetails` and `items` are copied from the order's snapshot; `totalAmount` becomes the shipment's declared value. The topic therefore contains personal data of the recipient. (`customerId` was nullable while guest orders existed; it is now always set.)

### `OrderShipped`
- **Producer:** Order Service
- **Consumers:** Inventory Service, Delivery Service, Notification Service
- **Trigger:** Inventory worker dispatches the parcel (`→ SHIPPED`).
- **Header:** Inventory and Delivery consume only records with `eventType: OrderShipped` from the shared topic.
- **Consumer Actions:**
  - *Inventory:* Permanently deducts `reservedQuantity` (consumes the reservation).
  - *Delivery:* Marks the shipment `IN_TRANSIT` and hands the parcel to the carrier, which later reports the delivery.
  - *Notification:* Sends tracking email.
- **Payload:**
  ```json
  {
    "eventId": "uuid",
    "orderId": "uuid",
    "ttn": "CARRIER-123456789",
    "customerEmail": "user@example.com",
    "timestamp": "2026-10-16T15:00:00Z"
  }
  ```

### `OrderCancelled`
- **Producer:** Order Service
- **Consumers:** Notification Service
- **Trigger:** The cancellation saga completes (`→ CANCELLED`) or the payment window expires (`→ EXPIRED`).
- **Payload:**
  ```json
  {
    "eventId": "uuid",
    "orderId": "uuid",
    "customerId": "uuid",
    "customerEmail": "user@example.com",
    "reason": "CUSTOMER_CANCELLED | EXPIRED",
    "timestamp": "2026-10-16T12:20:00Z"
  }
  ```
  (`reason` was `CUSTOMER_REQUESTED` here and `CUSTOMER_CANCELLED` in the Order and Notification specs; aligned to `CUSTOMER_CANCELLED` on 2026-10-16.)

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
  No `customerEmail`: customer contact data is not Delivery's responsibility. Notification Service resolves the recipient from its own read model `orderId → customerEmail`, built from the order events it consumes (decided 2026-10-18, D1 of the [Notification spec](../services-specs/notification-service-spec.md)).
