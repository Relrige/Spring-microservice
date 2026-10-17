---
status: TODO
service: order-service
---
# EP-ORD-EVT-01 and EP-ORD-EVT-02: Consume `TTNGenerated` and `OrderDelivered`

## Context
Delivery reports two milestones of a shipment on its topic: the TTN was registered (`PAID → PENDING_SHIPPING`) and the parcel was delivered (`SHIPPED → DELIVERED`). Both arrive on one topic, told apart by the `eventType` header, and can be redelivered. Order applies them only from the expected status, so a duplicate or a late event changes nothing.
*References:* [Order Spec — EP-ORD-EVT-01/02](../../design/services-specs/order-service-spec.md), [Event Catalogue — Delivery Updates](../../design/services-requirements/events-catalogue.md), [Delivery consumer task (the same pattern)](../delivery-service/10-consume-order-paid.md)

## Acceptance Criteria
- [ ] **One** `@KafkaListener` on `DELIVERY_EVENTS_TOPIC` (default `delivery.shipment-events`), group `order-service`, dispatching on `eventType`; other types are acknowledged and skipped.
- [ ] `TTNGenerated`: `PAID` → `PENDING_SHIPPING` (check-and-set); any other status → log and acknowledge (duplicate, worker already shipped, cancelled meanwhile).
- [ ] `OrderDelivered`: `SHIPPED` → `DELIVERED`; `DELIVERED` → duplicate; any other status → warning and acknowledge.
- [ ] Unknown `orderId` → warning and acknowledge.
- [ ] Unreadable payload → straight to `order.delivery.shipment-events.dlt` (declared by Order, 1 partition); other failures (an optimistic-lock conflict, the database down) → 4 retries with back-off, then the DLT. The missing topic is not fatal at startup.
- [ ] Tests (Testcontainers Kafka + PostgreSQL): each transition; each event twice; `TTNGenerated` after a cancellation leaves the order cancelled; a poison message goes to the DLT and a later record is processed.

## Technical Notes / Constraints
- Idempotency is by state, so no processed-events table is needed (as in Delivery and Customer); explain why in the task notes.
- Offsets are committed after the database transaction (ack mode `batch`).
