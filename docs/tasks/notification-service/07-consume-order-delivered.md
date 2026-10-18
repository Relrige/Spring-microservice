---
status: TODO
service: notification-service
---
# EP-NOTIF-EVT-04: Consume `OrderDelivered` from Delivery

## Context
`OrderDelivered` is published by **Delivery Service** on `delivery.shipment-events` when the carrier reports the delivery. Its payload is `{ eventId, orderId, ttn, timestamp }`, with no email address: customer contact data is not Delivery's concern. Notification stores the notification **without a recipient**, and the sender job takes the recipient from `order_recipients` at send time (D1).

Why not look the recipient up in the listener? The two topics are consumed independently. After an outage, Notification may read `OrderDelivered` before it has caught up with that order's `OrderShipped` on the other topic. A lookup in the listener would fail, use up the 4 retries in seconds and dead-letter a valid event. At send time the same lag only delays the email (task 09).
*References:* [Notification Spec — EP-NOTIF-EVT-04, Recipient read model](../../design/services-specs/notification-service-spec.md), [Delivery Spec — Shipment Events, EP-DEL-04](../../design/services-specs/delivery-service-spec.md), [Event Catalogue — `OrderDelivered`](../../design/services-requirements/events-catalogue.md)

## Acceptance Criteria
- [ ] `DeliveryEventsListener` (task 04) dispatches `OrderDelivered` to a handler; `TTNGenerated` stays skipped.
- [ ] Own record `OrderDeliveredEvent(eventId, orderId, ttn)` with all three fields required.
- [ ] The handler inserts a `PENDING` `ORDER_DELIVERED` notification with `recipient = null` (`insertIfAbsent`) and does **not** read or write `order_recipients`.
- [ ] Tests (Testcontainers Kafka + PostgreSQL, sender job disabled):
  - an `OrderDelivered` → one `PENDING` row without a recipient;
  - a duplicate → one row;
  - a `TTNGenerated` → no row;
  - a record without `ttn`, and one that is not JSON, go to `notification.delivery.shipment-events.dlt`;
  - an `OrderDelivered` for an order Notification has never seen is still stored (no failure, no DLT).

## Technical Notes / Constraints
- Delivery emits `OrderDelivered` once per shipment, but the outbox can still deliver it twice; the `eventId` dedup covers that.
- The Delivery spec's *Shipment Events* table is the contract. If Delivery's payload changes, this record changes with it.
