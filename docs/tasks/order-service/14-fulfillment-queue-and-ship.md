---
status: TODO
service: order-service
---
# EP-ORD-07 and EP-ORD-08: Fulfillment Queue and Dispatch (Inventory Worker)

## Context
Inventory workers see the paid orders waiting for dispatch, together with their shipment status from Delivery (TTN pending, failed or generated), and mark an order as shipped when the courier collects it. Dispatch is allowed only once Delivery has a TTN; `OrderShipped` then makes Inventory consume the reservation and Delivery hand the parcel to the carrier.
*References:* [Order Spec — EP-ORD-07, EP-ORD-08](../../design/services-specs/order-service-spec.md), [Delivery EP-DEL-01](../../design/services-specs/delivery-service-spec.md), [Inventory EP-INV-EVT-02](../../design/services-specs/inventory-service-spec.md), [Inventory Worker Scenarios (SCN-INV04, SCN-INV06)](../../design/scenarios/inventory-worker-scenarios.md)

## Acceptance Criteria
- [ ] `GET /orders/fulfillment-queue` (role `INVENTORY_WORKER`): orders in `PAID` or `PENDING_SHIPPING`, oldest `paidAt` first, merged with Delivery's `{ status, ttn, failureReason }` fetched in chunks of 100; `200 [ { orderId, status, placedAt, paidAt, items, deliveryDetails, deliveryStatus, ttn, failureReason } ]`.
- [ ] Delivery unavailable → orders without delivery data and `deliveryDataAvailable: false` (decide the response shape: an envelope or a header, and update the spec).
- [ ] `POST /orders/{orderId}/ship` (role `INVENTORY_WORKER`): not found → `404`; status not `PAID` / `PENDING_SHIPPING` → `409`; Delivery's shipment not `TTN_GENERATED` → `409`; Delivery unavailable → `503`; otherwise one transaction: check-and-set to `SHIPPED` + `OrderShipped { orderId, ttn, customerEmail }` in the outbox; `200 { orderId, status, ttn }`.
- [ ] An order still `PAID` whose shipment already has a TTN can be shipped (the `TTNGenerated` event is late).
- [ ] Tests: queue order and merge, chunking with more than 100 orders, Delivery down; ship for each Delivery status and order status; `OrderShipped` on the topic with the `eventType` header Inventory and Delivery filter on.

## Technical Notes / Constraints
- `GET /orders/fulfillment-queue` must not be caught by `GET /orders/{orderId}` (see [task 04](04-trusted-header-security.md)).
- Delivery status is never stored on the order; this endpoint asks for it every time.
