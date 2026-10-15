---
status: DONE
service: delivery-service
---
# EP-DEL-EVT-01: Consume `OrderPaid` → Create Shipment

## Context
A paid order is Delivery's trigger. `OrderPaid` is a **fat event** (decision 2026-10-15): it carries the recipient, carrier, items and total, so Delivery never calls Order back and keeps working when Order is down.

The listener only **creates** the shipment (`TTN_PENDING`). Carrier work and its retries run in the TTN generation job ([task 11](11-ttn-generation-job.md)), so a slow carrier never blocks the partition.

Order Service does not publish `OrderPaid` yet; tests publish it themselves using the payload from the event catalogue.
*References:* [Delivery Spec — EP-DEL-EVT-01](../../design/services-specs/delivery-service-spec.md), [Event Catalogue — `OrderPaid`](../../design/services-requirements/events-catalogue.md), [Inventory `OrderShipped` consumer](../inventory-service/16-consume-order-shipped.md)

## Acceptance Criteria
- [x] **One** `@KafkaListener` on `ORDER_EVENTS_TOPIC` (default `order.order-events`), group `delivery-service`, that dispatches by the `eventType` header: `OrderPaid` here, `OrderShipped` in [task 15](15-consume-order-shipped-and-delivery-report.md); other types are acknowledged and skipped.
- [x] The payload is read into Delivery's **own** record (`eventId`, `orderId`, `deliveryDetails`, `items`, `totalAmount`, `currency`); unknown properties are ignored. Required: `orderId`, the `deliveryDetails` fields, a non-empty `items` list with quantities ≥ 1, `totalAmount`, `currency`.
- [x] If a shipment for `orderId` exists (any status, including a `CANCELLED` tombstone) → acknowledge, no change. Otherwise create `Shipment.pending(...)` with `attempts = 0`, `next_attempt_at = now`.
- [x] Creation is race-safe on the unique `order_id` (e.g. `INSERT … ON CONFLICT DO NOTHING`, or catching the duplicate-key error and treating it as "already exists"); a concurrent cancel tombstone ([task 13](13-cancel-shipment.md)) wins without failing the consumer.
- [x] A payload that cannot be converted or fails validation is a poison message: no retries, sent to the dead-letter topic `delivery.order.order-events.dlt` (owned and declared by Delivery). Transient database errors are retried with bounded back-off.
- [x] A missing source topic is not fatal (`missing-topics-fatal: false`), checked by a startup test.
- [x] Tests (Testcontainers Kafka + PostgreSQL): a new event creates one `TTN_PENDING` shipment with items and recipient; the same event twice → one shipment; an event after a tombstone → stays `CANCELLED`; another `eventType` is ignored; a poison message goes to the DLT and does not block later messages.

## Technical Notes / Constraints
- Do not create a second listener container for `OrderShipped` in the same group on the same topic: the group would split the partitions between the two containers, and each would drop the other's event type.
- Offsets are committed only after the database transaction succeeded; state the acknowledgment mode.
- Idempotency is by state (the unique `orderId`), so no `processed_events` table is needed. Explain in the task notes why that is enough here, unlike Inventory's `ProductCreated` consumer.
- **Cross-service follow-up:** Order Service must publish the fat `OrderPaid` (EP-ORD-03 in the [Order spec](../../design/services-specs/order-service-spec.md)) through its own outbox. That is Order's backlog, not this task.

## Implementation Notes (2026-10-15)
- **One listener:** `events/OrderEventsListener.onOrderEvent(ConsumerRecord<String, String>)` on `${delivery.kafka.topics.order-events}`, group `delivery-service`. It reads the `eventType` header and dispatches: `OrderPaid` → `ShipmentService.createFromOrderPaid`; `OrderShipped` follows in task 15; any other type (or no header) is logged at `DEBUG` and acknowledged. It takes the raw record on purpose: with a `String` parameter the `StringJacksonJsonMessageConverter` would try to convert the JSON object into a `String` and fail.
- **Own record:** `events/OrderPaidEvent(eventId, orderId, deliveryDetails{name, phone, address, carrier}, items[{productId, quantity}], totalAmount, currency)`, unknown properties ignored; Bean Validation constraints mark the required fields (`items` non-empty, `quantity >= 1`, `totalAmount >= 0`, `currency` not blank). `eventId` is read but not required, because idempotency does not use it.
- **Poison messages:** invalid JSON or a broken constraint → `PoisonMessageException`, registered as not retryable on the `DefaultErrorHandler` → straight to `delivery.order.order-events.dlt` (declared by Delivery, 1 partition, `KafkaTopics.deadLetterTopicOf`). Other failures get 4 retries with back-off (1 s, 2 s, 4 s, 8 s) by seeking back, then the DLT. The error handler and the text `KafkaTemplate` that forwards to the DLT are copied from Inventory.
- **Race-safe creation:** `ShipmentService.insertIfAbsent(orderId, supplier)`: return if a shipment exists; otherwise `saveAndFlush` in its own transaction; on `DataIntegrityViolationException` (unique `order_id`), check again: if a row now exists (a concurrent tombstone or duplicate won), treat it as "already exists"; otherwise rethrow. Catching outside the transaction avoids continuing with a broken Hibernate session. The cancel endpoint (task 13) reuses it for the tombstone.
- **Acknowledgment mode:** `spring.kafka.listener.ack-mode: batch` (stated explicitly, as in Inventory): offsets are committed after the listener returned for every record of the poll, so only after the database transaction committed. `missing-topics-fatal: false`.
- **Why no `processed_events` table (unlike Inventory's `ProductCreated`):** the effect is visible in the state. The unique `orderId` makes a second `OrderPaid` find the row (any status, including a tombstone) and do nothing, so a redelivery cannot change anything. Inventory's `ProductCreated` consumer keeps a table because the group assignment asked to demonstrate the Idempotent Consumer pattern with keys in the database ([Catalog task 23](../catalog-service/23-retryable-topic-idempotent-consumer-and-report.md)); its handler would be idempotent by state too. A table is needed when the effect is not visible in the state (e.g. sending an e-mail or adding to a counter).
- **Verified (2026-10-15):** `OrderPaidConsumerIntegrationTest` (Testcontainers Kafka + PostgreSQL, the TTN job switched off by `delivery.ttn-job.enabled=false`): one `TTN_PENDING` shipment with recipient, carrier, items, declared value; duplicate → one shipment; event after a tombstone → stays `CANCELLED`; `OrderCancelled` and a record without header skipped; four poison messages (not JSON, empty `items`, missing `address`, `quantity` 0) reach the DLT without back-off and a later record of the same partition is processed; a transient database error is retried. `MissingSourceTopicsStartupTest`: without Order's topic the listener runs and readiness is `UP`.
