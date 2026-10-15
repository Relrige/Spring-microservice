---
status: TODO
service: delivery-service
---
# EP-DEL-EVT-01: Consume `OrderPaid` → Create Shipment

## Context
A paid order is Delivery's trigger. `OrderPaid` is a **fat event** (decision 2026-10-15): it carries the recipient, carrier, items and total, so Delivery never calls Order back and keeps working when Order is down.

The listener only **creates** the shipment (`TTN_PENDING`). Carrier work and its retries run in the TTN generation job ([task 11](11-ttn-generation-job.md)), so a slow carrier never blocks the partition.

Order Service does not publish `OrderPaid` yet; tests publish it themselves using the payload from the event catalogue.
*References:* [Delivery Spec — EP-DEL-EVT-01](../../design/services-specs/delivery-service-spec.md), [Event Catalogue — `OrderPaid`](../../design/services-requirements/events-catalogue.md), [Inventory `OrderShipped` consumer](../inventory-service/16-consume-order-shipped.md)

## Acceptance Criteria
- [ ] **One** `@KafkaListener` on `ORDER_EVENTS_TOPIC` (default `order.order-events`), group `delivery-service`, that dispatches by the `eventType` header: `OrderPaid` here, `OrderShipped` in [task 15](15-consume-order-shipped-and-delivery-report.md); other types are acknowledged and skipped.
- [ ] The payload is read into Delivery's **own** record (`eventId`, `orderId`, `deliveryDetails`, `items`, `totalAmount`, `currency`); unknown properties are ignored. Required: `orderId`, the `deliveryDetails` fields, a non-empty `items` list with quantities ≥ 1, `totalAmount`, `currency`.
- [ ] If a shipment for `orderId` exists (any status, including a `CANCELLED` tombstone) → acknowledge, no change. Otherwise create `Shipment.pending(...)` with `attempts = 0`, `next_attempt_at = now`.
- [ ] Creation is race-safe on the unique `order_id` (e.g. `INSERT … ON CONFLICT DO NOTHING`, or catching the duplicate-key error and treating it as "already exists"); a concurrent cancel tombstone ([task 13](13-cancel-shipment.md)) wins without failing the consumer.
- [ ] A payload that cannot be converted or fails validation is a poison message: no retries, sent to the dead-letter topic `delivery.order.order-events.dlt` (owned and declared by Delivery). Transient database errors are retried with bounded back-off.
- [ ] A missing source topic is not fatal (`missing-topics-fatal: false`), checked by a startup test.
- [ ] Tests (Testcontainers Kafka + PostgreSQL): a new event creates one `TTN_PENDING` shipment with items and recipient; the same event twice → one shipment; an event after a tombstone → stays `CANCELLED`; another `eventType` is ignored; a poison message goes to the DLT and does not block later messages.

## Technical Notes / Constraints
- Do not create a second listener container for `OrderShipped` in the same group on the same topic: the group would split the partitions between the two containers, and each would drop the other's event type.
- Offsets are committed only after the database transaction succeeded; state the acknowledgment mode.
- Idempotency is by state (the unique `orderId`), so no `processed_events` table is needed. Explain in the task notes why that is enough here, unlike Inventory's `ProductCreated` consumer.
- **Cross-service follow-up:** Order Service must publish the fat `OrderPaid` (EP-ORD-03 in the [Order spec](../../design/services-specs/order-service-spec.md)) through its own outbox. That is Order's backlog, not this task.
