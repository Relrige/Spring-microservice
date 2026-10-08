# Inventory Service — Implementation Spec

Owns stock counts, physical packaging dimensions, and stock reservations. It represents the **warehouse context** of a product: `StockItem` is a distinct aggregate from `Catalog.Product`, even though both reference the same `productId`. Catalog Service knows about commercial presentation; Inventory Service knows about physical warehouse reality.

It serves both the **Inventory Worker** (human-facing endpoints) and other **internal services** (machine-facing endpoints for reservations and readiness checks).

---

## Service Overview

| | |
|---|---|
| **Owned entities** | `StockItem` (`productId`, `availableQuantity`, `reservedQuantity`, `grossWeightGrams`, `lengthCm`, `widthCm`, `heightCm`); `StockItemReservation` (`id`, `orderId`, `productId`, `quantity`, `status`) |
| **Key enums** | `ReservationStatus { ACTIVE, RELEASED, CONSUMED }` |
| **Events emitted** | `ProductStockReplenished`, `ProductStockDepleted` |
| **Events consumed** | `ProductCreated` (from Catalog Service), `OrderShipped` (from Order Service) |

---

## EP-INV-01: View Stock Balances

**Type:** `HTTP GET /inventory/stock`
**Caller:** Inventory Worker (via API Gateway)
**Auth:** `ROLE_INVENTORY_WORKER`

**Steps:**
1. Query all `StockItem` records with optional filters (e.g., `?lowStock=true`, `?packagingMissing=true` — exact filter params TBD during implementation).
2. For each `StockItem`, compute `totalQuantity = availableQuantity + reservedQuantity` and `packagingConfigured` (true if all four dimension fields are non-null and > 0).
3. Return `200 OK [ { productId, availableQuantity, reservedQuantity, totalQuantity, packagingConfigured } ]`.

**Edge cases:**
- No stock items exist yet → return `200 OK []`

**External calls:** none
**Emits:** none
**Refs:** US-INV-04, SCN-INV03

---

## EP-INV-02: Adjust Stock Quantity (Restock / Write-off)

**Type:** `HTTP POST /inventory/stock/{productId}/adjust`
**Caller:** Inventory Worker (via API Gateway)
**Auth:** `ROLE_INVENTORY_WORKER`

**Steps:**
1. Validate request body: `delta` must be a non-zero integer (positive for restocking, negative for write-off / correction). A reason string is recommended but TBD.
2. Check if a `StockItem` exists for `productId`.
   - **If exists**: proceed to step 3.
   - **If not exists**: call Catalog Service synchronously: `GET /catalog/products/{productId}`.
     - Product not found in Catalog → `404 Not Found`. Do not create a `StockItem` for a non-existent product.
     - Product found → idempotently create `StockItem(productId, availableQuantity=0, reservedQuantity=0, packaging=null)` and proceed.
3. Validate that `availableQuantity + delta >= 0`. Cannot reduce available stock below zero.
4. Apply `availableQuantity += delta`.
5. Check for stock threshold crossings:
   - If `availableQuantity` just crossed **from 0 to > 0** → publish `ProductStockReplenished { productId, timestamp }`.
   - If `availableQuantity` just crossed **from > 0 to 0** → publish `ProductStockDepleted { productId, timestamp }`.
6. Return `200 OK { newAvailableQuantity }`.

> Restocking is permitted even for `NOT_ACTIVE` products — warehouse inventory can be prepared before a product goes live.

**Edge cases:**
- `delta` == 0 → `400 Bad Request` ("Delta must be non-zero")
- `availableQuantity + delta < 0` → `422 Unprocessable Entity` ("Insufficient available stock for write-off")
- `productId` not found in Catalog → `404 Not Found`

**External calls:**
- Catalog Service: `GET /catalog/products/{productId}` — purpose: verify product existence when `StockItem` is absent — **sync** (only if `StockItem` does not already exist)

**Emits:**
- `ProductStockReplenished { productId, timestamp }` → consumed by Catalog Service (EP-CAT-EVT-01), when product crosses 0→>0
- `ProductStockDepleted { productId, timestamp }` → consumed by Catalog Service (EP-CAT-EVT-02), when product crosses >0→0

**Refs:** US-INV-01, US-INV-03, SCN-INV01, SCN-S02, SCN-S03

---

## EP-INV-03: Set / Update Packaging Logistics Parameters

**Type:** `HTTP PUT /inventory/stock/{productId}/packaging`
**Caller:** Inventory Worker (via API Gateway)
**Auth:** `ROLE_INVENTORY_WORKER`

**Steps:**
1. Validate request body: `grossWeightGrams`, `lengthCm`, `widthCm`, `heightCm` — all must be positive integers.
2. Check if a `StockItem` exists for `productId`.
   - **If exists**: proceed to step 3.
   - **If not exists**: call Catalog Service synchronously: `GET /catalog/products/{productId}`.
     - Product not found → `404 Not Found`.
     - Product found → idempotently create `StockItem(productId, availableQuantity=0, reservedQuantity=0, packaging=null)` and proceed.
3. Update `StockItem` with the provided packaging values.
4. Return `200 OK`.

> Setting packaging dimensions is a **prerequisite for product activation**. Catalog Service will call EP-INV-04 (readiness check) before allowing activation, which verifies all four dimension fields are set.

**Edge cases:**
- Any dimension value ≤ 0 → `400 Bad Request`
- `productId` not found in Catalog → `404 Not Found`

**External calls:**
- Catalog Service: `GET /catalog/products/{productId}` — purpose: verify product existence when `StockItem` is absent — **sync** (only if `StockItem` does not already exist)

**Emits:** none
**Refs:** US-INV-02, SCN-INV02, N-03

---

## EP-INV-04: Check Stock Readiness (Internal — Used at Product Activation)

**Type:** `HTTP GET /inventory/stock/{productId}/readiness`
**Caller:** Catalog Service (internal, called during EP-CAT-03 activation flow)
**Auth:** Internal (no JWT; service-to-service trust)

**Steps:**
1. Look up `StockItem` by `productId`.
2. If `StockItem` does not exist → return `422 Unprocessable Entity { reason: "stock_item_not_found" }`.
3. If `StockItem` exists but any of `grossWeightGrams`, `lengthCm`, `widthCm`, `heightCm` is null or ≤ 0 → return `422 Unprocessable Entity { reason: "packaging_dimensions_missing" }`.
4. All checks pass → return `200 OK { ready: true }`.

**Edge cases:**
- `StockItem` not found → `422` (not `404`) so Catalog Service can return a clear activation failure reason to the manager

**External calls:** none
**Emits:** none
**Refs:** SCN-CM02 (step 2), N-03

---

## EP-INV-05: Get Packaging Dimensions (Internal — Used at TTN Generation)

**Type:** `HTTP GET /inventory/stock/{productId}/packaging`
**Caller:** Delivery Service (internal, called during automatic TTN generation)
**Auth:** Internal (no JWT; service-to-service trust)

**Steps:**
1. Look up `StockItem` by `productId`.
2. If not found → `404 Not Found`.
3. If packaging dimensions are null or incomplete → return `422 Unprocessable Entity { reason: "packaging_dimensions_missing" }`. This will cause the Delivery Service to flag the delivery as `TTN_FAILED`.
4. Return `200 OK { grossWeightGrams, lengthCm, widthCm, heightCm }`.

**Edge cases:**
- `StockItem` not found → `404 Not Found`
- Dimensions not configured → `422 Unprocessable Entity`

**External calls:** none
**Emits:** none
**Refs:** SCN-S04 (step 3), inventory-worker-technical-insights §2, N-08

---

## EP-INV-06: Reserve Stock (Internal — Used at Order Submission)

**Type:** `HTTP POST /inventory/reservations`
**Caller:** Order Service (internal, called during EP-ORD-02 hard reservation)
**Auth:** Internal (no JWT; service-to-service trust)

**Steps:**
1. Parse and validate the request body: `orderId` (UUID), `items: [ { productId, quantity } ]`. All quantities must be ≥ 1.
2. **Atomically** — in a single database transaction:
   a. For each item, check `StockItem.availableQuantity >= requestedQuantity`. If any item fails → abort the whole transaction.
   b. For each item, decrement `availableQuantity` by `quantity` and increment `reservedQuantity` by `quantity`.
   c. Create `StockItemReservation(id=UUID, orderId, productId, quantity, status=ACTIVE)` for each item.
3. Check stock threshold crossings for each updated `StockItem` (only for `ACTIVE` products):
   - If `availableQuantity` crosses from `> 0` to `0` → publish `ProductStockDepleted { productId }`.
4. Return `200 OK { reservationId, expiresAt }`. `expiresAt` is `now() + 15 minutes` (TTL defined by Order Service — passed in the request or computed here, TBD).

> Atomicity is critical. If stock is insufficient for even one item, no stock must be locked for any item in the batch.

**Edge cases:**
- Any item has insufficient `availableQuantity` → `422 Unprocessable Entity { insufficientItems: [ { productId, requested, available } ] }`
- A `productId` has no `StockItem` → treat as zero available; include in `insufficientItems`
- `orderId` already has an `ACTIVE` reservation → `409 Conflict` (idempotency guard — do not double-reserve)

**External calls:** none

**Emits:**
- `ProductStockDepleted { productId, timestamp }` → when any item's `availableQuantity` crosses >0→0 on an `ACTIVE` product

**Refs:** SCN-C14 (step 3), SCN-C15, N-06

---

## EP-INV-07: Release Stock Reservation (Internal — Used by Saga / TTL Expiry)

**Type:** `HTTP POST /inventory/reservations/{orderId}/release`
**Caller:** Order Service (internal, called during cancellation saga or TTL expiry flow)
**Auth:** Internal (no JWT; service-to-service trust)

**Steps:**
1. Find all `StockItemReservation` records for `orderId` with `status = ACTIVE`.
2. If none found → return `200 OK` (idempotent — already released or never existed).
3. **Atomically** — in a single database transaction:
   a. For each reservation, increment `StockItem.availableQuantity` by `quantity` and decrement `reservedQuantity` by `quantity`.
   b. Update each `StockItemReservation.status = RELEASED`.
4. Check stock threshold crossings for each updated `StockItem` (only for `ACTIVE` products):
   - If `availableQuantity` crosses from `0` to `> 0` → publish `ProductStockReplenished { productId }`.
5. Return `200 OK`.

**Edge cases:**
- No `ACTIVE` reservations found for `orderId` → idempotent success; return `200 OK`
- Partial release is not allowed — all items for the order are released together atomically

**External calls:** none

**Emits:**
- `ProductStockReplenished { productId, timestamp }` → when any item's `availableQuantity` crosses 0→>0 on an `ACTIVE` product

**Refs:** SCN-S06 (step 3), SCN-S07 (step 1)

---

## EP-INV-EVT-01: Consume `ProductCreated`

**Type:** Event consumer
**Producer:** Catalog Service
**Trigger:** A new product is created by the Catalog Manager (EP-CAT-01).

**Payload received:** `{ productId, timestamp }`

**Steps:**
1. Receive and deserialise the `ProductCreated` event.
2. Check whether a `StockItem` for `productId` already exists.
3. If it does → no action (idempotent). Acknowledge.
4. If it does not → create `StockItem(productId, availableQuantity=0, reservedQuantity=0, packaging=null)`.
5. Acknowledge the message.

> This handler is idempotent: if the event is delivered more than once (at-least-once broker delivery), the duplicate is safely ignored. A `StockItem` may also be created on-demand during a stock adjustment (EP-INV-02) if this event was missed — the system handles both paths.

**Edge cases:**
- Duplicate delivery of the same event → idempotent; acknowledge without creating a duplicate
- `productId` already has a `StockItem` (created by a prior stock write) → skip; acknowledge

**External calls:** none
**Refs:** SCN-S01, SCN-CM01 (step 5), N-04

---

## EP-INV-EVT-02: Consume `OrderShipped`

**Type:** Event consumer
**Producer:** Order Service
**Trigger:** Inventory worker confirms parcel handover to carrier; order status transitions to `SHIPPED`.

**Payload received:** `{ orderId, ttn }`

**Steps:**
1. Receive and deserialise the `OrderShipped` event.
2. Find all `StockItemReservation` records for `orderId` with `status = ACTIVE`.
3. If none found → log a warning and acknowledge (defensive; reservation may have already been consumed — do not fail).
4. **Atomically** — in a single database transaction:
   a. For each reservation, decrement `StockItem.reservedQuantity` by `quantity`. (Note: `availableQuantity` is **not** changed here — it was already decremented at reservation time.)
   b. Update each `StockItemReservation.status = CONSUMED`.
5. Acknowledge the message.

> This is the **permanent deduction** step. Stock was logically removed from available inventory at reservation time (EP-INV-06). This step moves it from "reserved" to "consumed", completing the physical fulfilment.

**Edge cases:**
- No `ACTIVE` reservations found for `orderId` → log warning; acknowledge (idempotent)
- Duplicate event delivery → second execution finds no `ACTIVE` reservations; idempotent acknowledge

**External calls:** none
**Refs:** SCN-INV06 (step 6), inventory-worker-technical-insights §4
