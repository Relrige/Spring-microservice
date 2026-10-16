---
status: DONE
service: customer-service
---
# EP-CUST-EVT-01: Consume `OrderPaid` → Clear Cart

## Context
Once an order is paid, the customer's server cart is cleared. Customer Service emits no events, so this is its only Kafka use: one consumer, no own topic, no outbox.

**Blocked for end-to-end checks:** Order Service does not publish `OrderPaid` yet. Tests publish the event themselves using the payload from the event catalogue.
*References:* [Customer Spec — EP-CUST-EVT-01](../../design/services-specs/customer-service-spec.md), [Event Catalogue — `OrderPaid`](../../design/services-requirements/events-catalogue.md), [Delivery `OrderPaid` consumer](../delivery-service/10-consume-order-paid.md), [Delivery Kafka setup](../delivery-service/05-kafka-spring-setup-and-topic.md)

## Acceptance Criteria
- [x] Add `spring-kafka` and the Kafka test dependencies; `SPRING_KAFKA_BOOTSTRAP_SERVERS` with the same defaults as the other services.
- [x] Consumer settings as in Delivery: group `customer-service`, `auto-offset-reset: earliest`, JSON read as `String` and converted with the same converter, unknown properties ignored. `ORDER_EVENTS_TOPIC` property (default `order.order-events`); Customer does **not** declare Order's topic.
- [x] One `@KafkaListener` that dispatches by the `eventType` header: `OrderPaid` is handled; other types are acknowledged and skipped.
- [x] The payload is read into Customer's **own** record with only the needed fields (`eventId`, `orderId`, `customerId`).
- [x] `customerId` null (guest order) → acknowledge, no change. Otherwise clear the cart through the same service operation as `DELETE /customers/cart`: lock if exists, `Cart.clear`, keep the `carts` row. No cart → acknowledge, no change.
- [x] A payload that cannot be converted is a poison message: no retries, sent to the dead-letter topic `customer.order.order-events.dlt` (owned and declared by Customer). Transient database errors are retried with bounded back-off.
- [x] A missing source topic is not fatal (`missing-topics-fatal: false`).
- [x] Tests (Testcontainers Kafka + PostgreSQL): an event for a customer with items empties the cart and keeps the row; the same event twice; a guest event; an event for a customer without a cart; another `eventType` is ignored; a poison message goes to the DLT and does not block later messages.

## Technical Notes / Constraints
- Idempotency is by state: clearing an empty cart is a no-op, so no `processed_events` table is needed. Explain in the task notes why that is enough here.
- Offsets are committed only after the database transaction succeeded; state the acknowledgment mode.
- **Known ordering edge case:** if the customer adds items after paying but before the event arrives, those items are cleared too. Accepted, because the event follows payment closely. Record it in the task notes.
- **Cross-service follow-up:** Order Service must publish `OrderPaid` with `customerId` (EP-ORD-03 in the [Order spec](../../design/services-specs/order-service-spec.md)). That is Order's backlog, not this task.

## Implementation Notes (2026-10-16)
- **Kafka settings** in `application.yml` as in Delivery: bootstrap `localhost:9094` (overridden by `SPRING_KAFKA_BOOTSTRAP_SERVERS`); consumer group `customer-service`, `auto-offset-reset: earliest`, string deserializers; `ack-mode: batch`; `missing-topics-fatal: false`. `customer.kafka.topics.order-events` = `${ORDER_EVENTS_TOPIC:order.order-events}`; Customer does not declare Order's topic. `.env.example` has both variables.
- **Producer:** Customer publishes no events of its own, so there is no own topic, no outbox and no second template. The only records it produces are failed records forwarded to its DLT, through Spring Boot's auto-configured `KafkaTemplate` with **string** key and value serializers (the consumed JSON text is forwarded unchanged; `acks=all`, idempotence, `max.block.ms=5000`). Delivery needs a separate "dead-letter template" only because its primary template serializes JSON.
- **No `RecordMessageConverter`:** the listener takes the raw `ConsumerRecord<String, String>` and parses the JSON itself (`JsonMapper` + `Validator`), by the type named in the `eventType` header, exactly as Delivery's listener does.
- **`OrderEventsListener`:** one `@KafkaListener`; `eventType` other than `OrderPaid` (or missing) → skipped. The payload is read into Customer's own `OrderPaidEvent(eventId, orderId, customerId)` with unknown properties ignored. `orderId` is required (contract), `customerId` is nullable: `null` → guest order, skipped. Otherwise `CartService.clearCart(customerId)`: the same operation as `DELETE /customers/cart` (lock if exists, delete items, keep the row; no cart → nothing created).
- **Error handling** (`KafkaConfig.kafkaErrorHandler`, copied from Delivery): `PoisonMessageException` (not JSON, `orderId` missing, a non-UUID value) → no retries, straight to `customer.order.order-events.dlt` (declared by Customer, 1 partition). Other failures → 4 retries with back-off 1 s, 2 s, 4 s, 8 s, then the DLT.
- **Idempotency by state:** clearing an empty or missing cart changes nothing, so a redelivered `OrderPaid` is harmless and no `processed_events` table is needed (unlike Inventory's `ProductCreated` consumer, where processing an event twice would change state twice).
- **Accepted ordering edge case:** items added after paying but before the event is processed are cleared too. The event follows the payment closely, so this window is short; preventing it would need the order's timestamp compared with each item's, which the spec does not ask for.
- **Tests:** every `@SpringBootTest` now also imports `KafkaTestContainerConfig`, so all of them share one cached context with a real broker (the listener runs in every full context).
- **Verified (2026-10-16):** `OrderPaidConsumerIntegrationTest` (7 cases: clears the customer's cart and keeps the row, other carts untouched; the same event twice; guest order; customer without a cart → no row; other and missing `eventType` skipped; three poison messages → DLT within 10 s without blocking a later event; a transient database failure is retried and succeeds), `MissingSourceTopicStartupTest` (without Order's topic: DLT exists, listener running, readiness `UP`), and the whole module's tests pass.
