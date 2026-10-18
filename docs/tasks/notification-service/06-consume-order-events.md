---
status: TODO
service: notification-service
---
# EP-NOTIF-EVT-01/02/03/05: Consume Order Events

## Context
Order Service publishes `OrderPlaced`, `OrderPaid`, `OrderShipped` and `OrderCancelled` on `order.order-events`, through its transactional outbox (at-least-once). Every one of them carries `customerEmail`. For each event, the handler does two things in **one transaction**:
1. upserts the recipient read model `order_recipients (orderId → customerEmail)` (D1);
2. inserts a `PENDING` notification keyed by `eventId`. A redelivered event inserts nothing, so it never causes a second email (Idempotent Consumer, D2).

Sending is not part of the handler (task 09), so an SMTP outage never affects the consumer.

Order Service already publishes these events, so the tests can use its exact payloads from the [Event Catalogue](../../design/services-requirements/events-catalogue.md).
*References:* [Notification Spec — EP-NOTIF-EVT-01, 02, 03, 05](../../design/services-specs/notification-service-spec.md), [Order Spec — Order Events](../../design/services-specs/order-service-spec.md), Order's event records in `order-service/.../order/events/`

## Acceptance Criteria
- [ ] Own records with only the fields Notification reads, plus Bean Validation for the required fields listed per handler in the spec:
  - `OrderPlacedEvent(eventId, orderId, customerEmail, totalAmount, currency, reservationExpiresAt)`
  - `OrderPaidEvent(eventId, orderId, customerEmail, totalAmount, currency, paidAt)`; `deliveryDetails` and `items` are not mapped
  - `OrderShippedEvent(eventId, orderId, ttn, customerEmail)`
  - `OrderCancelledEvent(eventId, orderId, customerEmail, reason)`, with `reason` as a `String`
- [ ] `customerEmail` must be a well-formed address (`@Email` + not blank); otherwise the record is poison.
- [ ] `NotificationService.record(...)`: in one transaction, `upsertRecipient` and then `insertIfAbsent` with the rendered email (task 05). A duplicate `eventId` is logged at `DEBUG`.
- [ ] `OrderCancelled`: `CUSTOMER_CANCELLED` → `ORDER_CANCELLED`, `EXPIRED` → `ORDER_EXPIRED`, any other value → `ORDER_CANCELLED` and a `WARN` log line.
- [ ] Tests (Testcontainers Kafka + PostgreSQL, the sender job disabled with `notification.sender.enabled=false`):
  - each of the four events → one `PENDING` row of the right type with recipient, subject and body, and an `order_recipients` row;
  - the same record twice → one row;
  - two events of one order → two rows and one recipient;
  - an unknown `reason` → `ORDER_CANCELLED`;
  - poison records go to `notification.order.order-events.dlt`: not JSON, missing `eventId`, malformed `customerEmail`, `OrderShipped` without `ttn`;
  - a transient database failure is retried and then stored once.

## Technical Notes / Constraints
- Order's `OrderPaid` is a fat event with personal data (`deliveryDetails`). It is ignored here and must never appear in logs or exception messages.
- If the upsert succeeds and the insert finds a duplicate, the transaction still commits. That is correct: the recipient is the same snapshot.
- Do not reuse Order's event classes across modules. Each service owns its own records (tolerant reader); there is no shared library in this project.
