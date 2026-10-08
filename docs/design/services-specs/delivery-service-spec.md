# Delivery Service — Implementation Spec

An Anti-Corruption Layer (ACL) for external postal carrier integration. It is the **sole service** that knows about carrier protocols, TTN formats, and carrier webhooks. No other service stores or processes carrier-specific data.

It owns `Shipment` — the internal representation of a parcel registration with the carrier. TTN generation is triggered automatically when payment is confirmed, with a three-tier resilience mechanism for carrier failures.

A `MockCarrierGateway` is used during development to simulate carrier responses. Real carrier integration (e.g., Nova Poshta API) requires only swapping the gateway implementation.

---

## Service Overview

| | |
|---|---|
| **Owned entities** | `Shipment` (`id`, `orderId`, `ttn`, `status`, `failureReason`, `recipientName`, `recipientPhone`, `recipientAddress`, `totalWeightGrams`, `lengthCm`, `widthCm`, `heightCm`, `declaredValue`, `createdAt`, `updatedAt`) |
| **Key enums** | `ShipmentStatus { TTN_PENDING, TTN_FAILED, TTN_GENERATED, DELIVERED }` |
| **Events emitted** | `OrderDelivered` |
| **Events consumed** | `OrderPaid` (from Order Service) |
| **Callers** | Order Service (internal), Inventory Worker (via API Gateway), Carrier gateway (webhook) |

---

## EP-DEL-EVT-01: Consume `OrderPaid` → Initiate TTN Generation

**Type:** Event consumer
**Producer:** Order Service
**Trigger:** Customer successfully completes payment; order status transitions to `PAID`.

**Payload received:** `{ orderId, customerId (nullable), customerEmail }`

> This consumer must also obtain order details (recipient info, items, declared value) to register the shipment. Order Service should include them in the event payload or Delivery Service fetches them via a sync call — implementation approach TBD. The steps below assume the payload includes recipient details and item list, or Delivery Service calls Order Service internally.

**Steps:**
1. Receive and deserialise the `OrderPaid` event.
2. Create a `Shipment` record: `status = TTN_PENDING`, `orderId`, recipient details from the order.
3. For each item in the order, call Inventory Service synchronously to fetch packaging dimensions: `GET /inventory/stock/{productId}/packaging`.
4. Compute `totalWeightGrams` by summing `grossWeightGrams × quantity` for all items. Compute the dominant box dimensions (TBD — e.g., largest single item, or a combined box — implementation detail).
5. Call the carrier gateway to register the consignment: `register(recipientDetails, parcels, declaredValue)`.
6. **On carrier success:**
   - Store the returned `ttn`.
   - Update `Shipment.status = TTN_GENERATED`, `Shipment.ttn = ttn`.
   - Acknowledge the message.
7. **On carrier failure (first attempt):**
   - Apply **Tier 1 automatic retries with exponential backoff**: retry up to 3 times (e.g., after 5s, 30s, 2 min) using Spring Retry / Resilience4j.
   - If retries succeed → same as step 6.
   - If all retries fail → proceed to step 8.
8. **All retries exhausted:**
   - Update `Shipment.status = TTN_FAILED`, `Shipment.failureReason = <carrier error message>`.
   - Acknowledge the message (do not reject / requeue — the failure is now tracked in the `Shipment` record for manual remediation via EP-DEL-03).

**Edge cases:**
- Inventory Service returns `422` (packaging dimensions missing) for any item → update `Shipment.status = TTN_FAILED` with `reason = "packaging_dimensions_missing_for_{productId}"`; acknowledge (N-08)
- Inventory Service returns `404` for a product → `TTN_FAILED` with appropriate reason
- Carrier gateway timeout on every retry attempt → `TTN_FAILED`
- Duplicate `OrderPaid` event delivery → check if `Shipment` for `orderId` already exists; if `TTN_GENERATED` → acknowledge without re-registering (idempotent)

**External calls:**
- Inventory Service: `GET /inventory/stock/{productId}/packaging` per order item — purpose: fetch box dimensions — **sync**
- `MockCarrierGateway` / real carrier: register consignment — **sync** (with retries)

**Refs:** SCN-S04, SCN-S05, inventory-worker-technical-insights §3, inventory-worker-technical-insights §5, N-08

---

## EP-DEL-01: Batch Get Shipment Statuses (Internal)

**Type:** `HTTP GET /delivery/shipments?orderIds={uuid1,uuid2,...}`
**Caller:** Order Service (internal, called when rendering the fulfillment queue EP-ORD-07)
**Auth:** Internal (no JWT; service-to-service trust)

**Steps:**
1. Parse the `orderIds` query parameter as a comma-separated list of UUIDs.
2. Query all `Shipment` records where `orderId` is in the list.
3. Return `200 OK [ { orderId, status, ttn, failureReason } ]`.
4. For `orderId` values with no corresponding `Shipment` record, include them in the response with `status = null` (pending creation — the `OrderPaid` event may not have been processed yet).

**Edge cases:**
- Empty `orderIds` list → `400 Bad Request`
- None of the requested order IDs have `Shipment` records → return array of items with `status = null`

**External calls:** none
**Emits:** none
**Refs:** SCN-INV04 (step 3), api-contracts.md

---

## EP-DEL-02: Cancel Shipment / TTN (Internal — Used by Cancellation Saga)

**Type:** `HTTP DELETE /delivery/shipments/{orderId}`
**Caller:** Order Service (internal, called during the cancellation saga step for Delivery Service)
**Auth:** Internal (no JWT; service-to-service trust)

**Steps:**
1. Look up `Shipment` by `orderId`.
2. If no `Shipment` record found → return `200 OK` (idempotent — nothing to cancel).
3. If `Shipment.status == TTN_PENDING` or `TTN_FAILED` → no carrier call needed (no TTN was issued). Update `Shipment.status = CANCELLED` (if that status is added) or simply delete the record. Return `200 OK`.
4. If `Shipment.status == TTN_GENERATED`:
   - Call the carrier gateway to cancel the registered consignment using the `ttn`.
   - On success: mark the `Shipment` as cancelled. Return `200 OK`.
   - On carrier failure: log the error, record the failure reason. Return `200 OK` (the saga must not be blocked by a carrier cancellation failure — the parcel has not been handed over yet; TTN cancellation is best-effort at this stage).
5. If `Shipment.status == DELIVERED` → return `409 Conflict` ("Cannot cancel a delivered shipment"). This should not be reachable if the order cancellation gate (locked at `SHIPPED`) is correctly enforced.

**Edge cases:**
- `Shipment` not found → idempotent `200 OK`
- `TTN_GENERATED` and carrier cancellation fails → log and return `200 OK` (best-effort cancellation)
- `DELIVERED` status → `409 Conflict`

**External calls:**
- `MockCarrierGateway` / real carrier: cancel consignment by TTN — **sync** (only if `TTN_GENERATED`)

**Emits:** none
**Refs:** SCN-S07 (step 2)

---

## EP-DEL-03: Manual TTN Generation Retry

**Type:** `HTTP POST /delivery/orders/{orderId}/ttn/retry`
**Caller:** Inventory Worker (via API Gateway)
**Auth:** `ROLE_INVENTORY_WORKER`

**Steps:**
1. Look up `Shipment` by `orderId`.
2. If not found → `404 Not Found`.
3. If `Shipment.status != TTN_FAILED` → `409 Conflict` ("Retry is only allowed for shipments in TTN_FAILED status").
4. Re-attempt carrier gateway registration (same logic as EP-DEL-EVT-01 steps 3–6):
   a. Re-fetch packaging dimensions from Inventory Service if not cached on the `Shipment`.
   b. Call carrier gateway to register the consignment.
5. **On success:**
   - Update `Shipment.ttn`, `Shipment.status = TTN_GENERATED`, clear `failureReason`.
   - Return `200 OK { ttn }`.
6. **On failure:**
   - Update `Shipment.failureReason` with the new error from this attempt.
   - Return `424 Failed Dependency { reason }`.

**Edge cases:**
- `Shipment` not found → `404 Not Found`
- `Shipment` not in `TTN_FAILED` state → `409 Conflict`
- Inventory Service still returns missing dimensions → `TTN_FAILED` remains; worker must fix dimensions first via EP-INV-03
- Carrier still unavailable → `424 Failed Dependency`

**External calls:**
- Inventory Service: `GET /inventory/stock/{productId}/packaging` — **sync** (if dimensions not already stored)
- `MockCarrierGateway` / real carrier: register consignment — **sync**

**Emits:** none
**Refs:** US-INV-06, SCN-INV05, SCN-S05 (Tier 3)

---

## EP-DEL-04: Carrier Delivery Confirmation Webhook

**Type:** `HTTP POST /delivery/carrier-webhook`
**Caller:** Carrier gateway (external push) — or `MockCarrierGateway` internal simulation
**Auth:** None (or webhook secret / HMAC signature verification — implementation TBD)

**Steps:**
1. Parse and validate the webhook payload: `{ orderId, ttn, status }`. Verify webhook authenticity if a secret is configured.
2. Look up `Shipment` by `orderId` (or by `ttn`).
3. If `Shipment` not found → `200 OK` (acknowledge to prevent carrier retries; log the unknown `orderId`).
4. If `status == DELIVERED`:
   - Update `Shipment.status = DELIVERED`, `Shipment.updatedAt = now()`.
   - Publish event `OrderDelivered { orderId, ttn }`.
   - Return `200 OK`.
5. Other carrier statuses (e.g., `IN_TRANSIT`) may be stored for future display but do not trigger domain events at this stage (TBD — depends on how granular tracking the system needs).

**Edge cases:**
- Unknown `orderId` or `ttn` → log and return `200 OK` (do not return `404` — this could cause excessive carrier retries)
- Duplicate `DELIVERED` webhook → `Shipment` already `DELIVERED`; publish event again only if idempotency guarantees are needed (or check before publishing); return `200 OK`
- Payload malformed → `400 Bad Request`

**External calls:** none

**Emits:** `OrderDelivered { orderId, ttn }` → consumed by Order Service (EP-ORD-EVT-02) and Notification Service (EP-NOTIF-EVT-04)

**Refs:** SCN-S08, inventory-worker-technical-insights §6
