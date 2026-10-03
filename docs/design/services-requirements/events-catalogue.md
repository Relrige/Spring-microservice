# Event Catalogue

> This document defines the asynchronous Domain Events published to the message broker (RabbitMQ / Kafka).
> These events facilitate decoupling for non-blocking side-effects.
> *Note: With Orchestration selected for Sagas, workflow commands (like "Release Stock" or "Refund") are handled via synchronous REST calls rather than events.*

---

## 1. Catalog & Inventory Synchronization

### `ProductCreated`
- **Producer:** Catalog Service
- **Consumers:** Inventory Service
- **Trigger:** A new product is created by the Catalog Manager.
- **Payload:**
  ```json
  {
    "productId": "uuid",
    "timestamp": "2023-10-01T12:00:00Z"
  }
  ```

### `ProductStockReplenished`
- **Producer:** Inventory Service
- **Consumers:** Catalog Service
- **Trigger:** `availableQuantity` for an `ACTIVE` product transitions from `0` to `> 0`.
- **Payload:**
  ```json
  {
    "productId": "uuid",
    "timestamp": "2023-10-01T12:00:00Z"
  }
  ```

### `ProductStockDepleted`
- **Producer:** Inventory Service
- **Consumers:** Catalog Service
- **Trigger:** `availableQuantity` for an `ACTIVE` product transitions from `> 0` to `0`.
- **Payload:**
  ```json
  {
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
