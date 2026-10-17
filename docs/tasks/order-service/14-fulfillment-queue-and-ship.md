---
status: DONE
service: order-service
---
# EP-ORD-07 and EP-ORD-08: Fulfillment Queue and Dispatch (Inventory Worker)

## Context
Inventory workers see the paid orders waiting for dispatch, together with their shipment status from Delivery (TTN pending, failed or generated), and mark an order as shipped when the courier collects it. Dispatch is allowed only once Delivery has a TTN; `OrderShipped` then makes Inventory consume the reservation and Delivery hand the parcel to the carrier.
*References:* [Order Spec — EP-ORD-07, EP-ORD-08](../../design/services-specs/order-service-spec.md), [Delivery EP-DEL-01](../../design/services-specs/delivery-service-spec.md), [Inventory EP-INV-EVT-02](../../design/services-specs/inventory-service-spec.md), [Inventory Worker Scenarios (SCN-INV04, SCN-INV06)](../../design/scenarios/inventory-worker-scenarios.md)

## Acceptance Criteria
- [x] `GET /orders/fulfillment-queue` (role `INVENTORY_WORKER`): orders in `PAID` or `PENDING_SHIPPING`, oldest `paidAt` first, merged with Delivery's `{ status, ttn, failureReason }` fetched in chunks of 100; `200 [ { orderId, status, placedAt, paidAt, items, deliveryDetails, deliveryStatus, ttn, failureReason } ]`.
- [x] Delivery unavailable → orders without delivery data and `deliveryDataAvailable: false` (decide the response shape: an envelope or a header, and update the spec).
- [x] `POST /orders/{orderId}/ship` (role `INVENTORY_WORKER`): not found → `404`; status not `PAID` / `PENDING_SHIPPING` → `409`; Delivery's shipment not `TTN_GENERATED` → `409`; Delivery unavailable → `503`; otherwise one transaction: check-and-set to `SHIPPED` + `OrderShipped { orderId, ttn, customerEmail }` in the outbox; `200 { orderId, status, ttn }`.
- [x] An order still `PAID` whose shipment already has a TTN can be shipped (the `TTNGenerated` event is late).
- [x] Tests: queue order and merge, chunking with more than 100 orders, Delivery down; ship for each Delivery status and order status; `OrderShipped` on the topic with the `eventType` header Inventory and Delivery filter on.

## Technical Notes / Constraints
- `GET /orders/fulfillment-queue` must not be caught by `GET /orders/{orderId}` (see [task 04](04-trusted-header-security.md)).
- Delivery status is never stored on the order; this endpoint asks for it every time.

## Implementation
Done on 2026-10-17:
- `controllers/FulfillmentController` (`GET /orders/fulfillment-queue`, `POST /orders/{orderId}/ship`) → `services/FulfillmentService`. The security rules from task 04 already put the queue before `GET /orders/{orderId}`; `RouteSecurityRulesTest` now runs against the real controllers only.
- **Queue:** `findByStatusInOrderByPaidAtAsc(PAID, PENDING_SHIPPING)` (items fetched with the orders), one `Shipments.find` for all of them (the adapter splits into chunks of 100), merged per order into `dto/FulfillmentQueueResponse { deliveryDataAvailable, orders: [ { orderId, status, placedAt, paidAt, items: [ { productId, productTitle, quantity } ], deliveryDetails, deliveryStatus, ttn, failureReason } ] }`. An empty queue does not call Delivery; Delivery unavailable → `deliveryDataAvailable: false` and the orders without delivery data.
- **Ship:** `404` for an unknown order; not `PAID` / `PENDING_SHIPPING` → `409` without asking Delivery; Delivery's shipment missing or not `TTN_GENERATED` (or without a TTN) → `TtnNotGeneratedException` (`409`); Delivery unavailable → `503`; then one transaction: re-read, check again, `markShipped()` (also from `PAID`), `OrderShippedEvent { orderId, ttn, customerEmail }` in the outbox, `200 dto/ShippedOrderResponse { orderId, status, ttn }`. A version conflict (usually `TTNGenerated` moving `PAID → PENDING_SHIPPING` at the same moment) re-reads and retries, at most three times.
- `support/OrderRoutesProbeController` is deleted: every route now has its real controller.
- Tests: `FulfillmentServiceTest` (18 with parameters: merge in `paidAt` order with Delivery's answer in another order, a failure reason and a missing shipment; Delivery down; empty queue without a call; ship from `PAID` and `PENDING_SHIPPING`; four Delivery statuses and no shipment → `409`; five order statuses → `409` without a call; `404`; `503`; the race with `TTNGenerated`; a second ship refused), `FulfillmentControllerTest` (4), `integration/FulfillmentAndExpiryIntegrationTest` (4: 105 paid orders → two Delivery calls and the merge in order, an unpaid order not listed; Delivery down → flag `false`; ship → `SHIPPED` and `OrderShipped` on the topic with key, `eventType` header, TTN and email; TTN pending → `409` and still `PAID`).

## Decisions
Taken on 2026-10-17 (the criterion asked to decide):
- **The queue answers an envelope `{ deliveryDataAvailable, orders }`, not a bare list with a header.** A flag in the body cannot be lost by a proxy or overlooked by a client, and the shape leaves room for later additions. The spec (EP-ORD-07) and the API contracts are updated.
- **Items in the queue carry no prices**: a worker picks products, and the snapshot prices are customer data the queue does not need.
