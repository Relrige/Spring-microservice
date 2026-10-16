---
status: TODO
service: customer-service
---
# EP-CUST-EVT-01: Consume `OrderPaid` → Clear Cart

## Context
Once an order is paid, the customer's server cart is cleared. Customer Service emits no events, so this is its only Kafka use: one consumer, no own topic, no outbox.

**Blocked for end-to-end checks:** Order Service does not publish `OrderPaid` yet. Tests publish the event themselves using the payload from the event catalogue.
*References:* [Customer Spec — EP-CUST-EVT-01](../../design/services-specs/customer-service-spec.md), [Event Catalogue — `OrderPaid`](../../design/services-requirements/events-catalogue.md), [Delivery `OrderPaid` consumer](../delivery-service/10-consume-order-paid.md), [Delivery Kafka setup](../delivery-service/05-kafka-spring-setup-and-topic.md)

## Acceptance Criteria
- [ ] Add `spring-kafka` and the Kafka test dependencies; `SPRING_KAFKA_BOOTSTRAP_SERVERS` with the same defaults as the other services.
- [ ] Consumer settings as in Delivery: group `customer-service`, `auto-offset-reset: earliest`, JSON read as `String` and converted with the same converter, unknown properties ignored. `ORDER_EVENTS_TOPIC` property (default `order.order-events`); Customer does **not** declare Order's topic.
- [ ] One `@KafkaListener` that dispatches by the `eventType` header: `OrderPaid` is handled; other types are acknowledged and skipped.
- [ ] The payload is read into Customer's **own** record with only the needed fields (`eventId`, `orderId`, `customerId`).
- [ ] `customerId` null (guest order) → acknowledge, no change. Otherwise clear the cart through the same service operation as `DELETE /customers/cart`: lock if exists, `Cart.clear`, keep the `carts` row. No cart → acknowledge, no change.
- [ ] A payload that cannot be converted is a poison message: no retries, sent to the dead-letter topic `customer.order.order-events.dlt` (owned and declared by Customer). Transient database errors are retried with bounded back-off.
- [ ] A missing source topic is not fatal (`missing-topics-fatal: false`).
- [ ] Tests (Testcontainers Kafka + PostgreSQL): an event for a customer with items empties the cart and keeps the row; the same event twice; a guest event; an event for a customer without a cart; another `eventType` is ignored; a poison message goes to the DLT and does not block later messages.

## Technical Notes / Constraints
- Idempotency is by state: clearing an empty cart is a no-op, so no `processed_events` table is needed. Explain in the task notes why that is enough here.
- Offsets are committed only after the database transaction succeeded; state the acknowledgment mode.
- **Known ordering edge case:** if the customer adds items after paying but before the event arrives, those items are cleared too. Accepted, because the event follows payment closely. Record it in the task notes.
- **Cross-service follow-up:** Order Service must publish `OrderPaid` with `customerId` (EP-ORD-03 in the [Order spec](../../design/services-specs/order-service-spec.md)). That is Order's backlog, not this task.
