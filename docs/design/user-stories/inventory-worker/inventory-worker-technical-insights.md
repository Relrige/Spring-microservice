# Inventory Worker — Technical & Architectural Insights

This document captures architectural discoveries, domain boundaries, and technical trade-offs relevant to the **Inventory Worker** actor and the **Inventory / Delivery Services**.

---

## 1. Polysemic Entities: Commercial Product vs. Warehouse Stock Item
- **DDD Concept:** The same real-world concept ("Product") represents different aggregates across bounded contexts:
  - In `Catalog Service`: A commercial showcase offer (`Product`) with title, description, category, and base price.
  - In `Inventory Service`: A physical warehouse asset (`StockItem`) with `availableQuantity`, `reservedQuantity`, gross packaged weight, and box dimensions.
- **Mastery & Initialization:**
  - `Catalog Service` is the originator of the commercial product and generates the `productId`.
  - When a product is created, an asynchronous domain event `ProductCreated(productId)` initializes a corresponding `StockItem` in `Inventory Service` with 0 available stock.
  - Warehouse workers then manage physical counts and packaging dimensions for that `productId`.

---

## 2. Logistical Parameters Ownership
- **Decision:** Packaged gross weight (grams) and shipping carton dimensions (L x W x H in cm) belong exclusively to the **Inventory Context**, not Catalog.
- **Rationale:** 
  - Catalog customers care about device specifications (e.g. phone weight = 180g).
  - Shipping carriers and warehouse shelves care about the gross packaging (phone + charger + retail box + shipping carton + protective wrapping = 450g, 22x12x6 cm).
  - Isolating logistical dimensions in `Inventory Service` prevents warehouse packaging changes from polluting the customer-facing catalog.

---

## 3. Automatic Event-Driven TTN Generation
- **Decision:** The postal tracking number (waybill / TTN) is automatically generated upon payment confirmation rather than manually triggered by the warehouse worker.
- **Workflow:**
  1. Customer completes card payment $\to$ `Payment Service` emits `PaymentSuccessful`.
  2. `Order Service` transitions status to `PAID` and emits `OrderPaid`.
  3. `Delivery Service` consumes `OrderPaid`, fetches parcel weight/dimensions, and calls the carrier gateway to register the electronic consignment.
  4. The carrier gateway returns the generated TTN.
  5. When the Inventory Worker opens the paid order in the warehouse fulfillment queue, the TTN is already attached and ready for one-click label printing.

---

## 4. Fulfillment & Dispatch Lifecycle
- **Transition to SHIPPED:**
  - The worker marks the order as dispatched:
    - Order status transitions from `PAID` (or `PACKING`) to `SHIPPED`.
    - `Inventory Service` permanently deducts the items from `reservedQuantity`.
    - Event `OrderShipped(orderId, ttn)` is published $\to$ `Notification Service` dispatches an email to the customer with their tracking number.
- **Cancellation Boundary:** Once the order reaches `SHIPPED`, customer self-service cancellation is strictly locked.

---

## 5. Resilience & Failure Handling in Carrier Integration
- **The Golden Rule:** Never automatically cancel a paid order or refund the customer simply because the external postal carrier API experienced an outage or timeout. Money is collected, stock is reserved; the failure is merely an integration glitch.
- **Three-Tier Resilience Architecture:**
  1. **Tier 1 (Automated Retries with Backoff):** `Delivery Service` automatically retries carrier registration (e.g. 3 attempts with exponential backoff: 5s, 30s, 2min) using Spring Retry / Resilience4j to absorb transient network blips.
  2. **Tier 2 (`TTN_FAILED` Sub-Status & Dead-Letter Queue):** If all automated retries fail (e.g. prolonged carrier downtime or malformed recipient address), the order remains in `PAID` / `PROCESSING`, but its delivery state is flagged as `TTN_FAILED` in the warehouse queue.
  3. **Tier 3 (Manual Remediation by Inventory Worker):** The inventory worker sees the error reason on the fulfillment dashboard and has a dedicated "Retry TTN Generation" action once the carrier is back online.
- **Architectural Value:** Demonstrates fault isolation, graceful degradation, and preventing cascading failures from 3rd-party dependencies.
